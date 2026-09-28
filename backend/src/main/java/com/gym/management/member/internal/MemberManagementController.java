package com.gym.management.member.internal;

import com.gym.management.auth.AuditTrail;
import com.gym.management.auth.AuthenticatedStaff;
import jakarta.persistence.LockModeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

interface MemberRepository extends JpaRepository<MemberEntity, Long> {
    @Query("""
            select member from MemberEntity member
             where member.gymId = :gymId
               and member.status in (com.gym.management.member.internal.MemberStatus.ACTIVE,
                                     com.gym.management.member.internal.MemberStatus.CONSULTING,
                                     com.gym.management.member.internal.MemberStatus.EXPIRED)
               and (:status is null or member.status = :status)
               and (:groupId is null or member.memberGroupId = :groupId)
               and (:query is null
                    or lower(member.name) like lower(concat('%', :query, '%'))
                    or lower(member.memberNumber) like lower(concat('%', :query, '%'))
                    or member.normalizedPhone like concat('%', :normalizedQuery, '%'))
            """)
    Page<MemberEntity> searchVisible(
            @Param("gymId") long gymId,
            @Param("query") String query,
            @Param("normalizedQuery") String normalizedQuery,
            @Param("status") MemberStatus status,
            @Param("groupId") Long groupId,
            Pageable pageable
    );

    @Query("""
            select member from MemberEntity member
             where member.gymId = :gymId
               and ((:phone is not null and member.normalizedPhone = :phone)
                    or (lower(member.name) = lower(:name) and member.birthDate = :birthDate))
             order by member.createdAt desc, member.id desc
            """)
    List<MemberEntity> findDuplicateCandidates(
            @Param("gymId") long gymId,
            @Param("name") String name,
            @Param("birthDate") LocalDate birthDate,
            @Param("phone") String phone
    );

    Optional<MemberEntity> findByIdAndGymId(Long id, Long gymId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from MemberEntity member where member.id = :memberId and member.gymId = :gymId")
    Optional<MemberEntity> findForUpdateByIdAndGymId(@Param("memberId") long memberId, @Param("gymId") long gymId);
}

interface MemberNumberSequenceRepository extends JpaRepository<MemberNumberSequenceEntity, MemberNumberSequenceId> {
    @Modifying
    @Query(value = """
            insert into member_number_sequences (gym_id, year_month, last_value, updated_at)
            values (:gymId, :yearMonth, 0, current_timestamp)
            on conflict (gym_id, year_month) do nothing
            """, nativeQuery = true)
    void ensureExists(@Param("gymId") long gymId, @Param("yearMonth") String yearMonth);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sequence from MemberNumberSequenceEntity sequence where sequence.id = :id")
    Optional<MemberNumberSequenceEntity> findForUpdate(@Param("id") MemberNumberSequenceId id);
}

interface MemberStatusHistoryRepository extends JpaRepository<MemberStatusHistoryEntity, Long> {
    List<MemberStatusHistoryEntity> findAllByMemberIdOrderByEffectiveAtDescIdDesc(Long memberId);
}

interface MemberGroupHistoryRepository extends JpaRepository<MemberGroupHistoryEntity, Long> {
    List<MemberGroupHistoryEntity> findAllByMemberIdOrderByEffectiveOnDescIdDesc(Long memberId);
}

interface GuardianRepository extends JpaRepository<GuardianEntity, Long> {
}

interface MemberGuardianRepository extends JpaRepository<MemberGuardianEntity, MemberGuardianId> {
    @Query("""
            select link from MemberGuardianEntity link
             where link.id.memberId = :memberId
             order by link.isPrimary desc, link.createdAt asc
            """)
    List<MemberGuardianEntity> findAllForMember(@Param("memberId") Long memberId);
}

interface MemberConsentRepository extends JpaRepository<MemberConsentEntity, Long> {
    List<MemberConsentEntity> findAllByMemberIdOrderByDecidedAtDescIdDesc(Long memberId);
}

interface MemberNoteRepository extends JpaRepository<MemberNoteEntity, Long> {
}

@Service
class MemberService {
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String CONSENT_DOCUMENT_VERSION = "2026-09-01";

    private final MemberRepository members;
    private final MemberNumberSequenceRepository sequences;
    private final MemberGroupRepository groups;
    private final MemberStatusHistoryRepository statusHistory;
    private final MemberGroupHistoryRepository groupHistory;
    private final GuardianRepository guardians;
    private final MemberGuardianRepository memberGuardians;
    private final MemberConsentRepository consents;
    private final MemberNoteRepository notes;
    private final AuditTrail auditTrail;
    private final Clock clock;

    MemberService(
            MemberRepository members,
            MemberNumberSequenceRepository sequences,
            MemberGroupRepository groups,
            MemberStatusHistoryRepository statusHistory,
            MemberGroupHistoryRepository groupHistory,
            GuardianRepository guardians,
            MemberGuardianRepository memberGuardians,
            MemberConsentRepository consents,
            MemberNoteRepository notes,
            AuditTrail auditTrail
    ) {
        this.members = members;
        this.sequences = sequences;
        this.groups = groups;
        this.statusHistory = statusHistory;
        this.groupHistory = groupHistory;
        this.guardians = guardians;
        this.memberGuardians = memberGuardians;
        this.consents = consents;
        this.notes = notes;
        this.auditTrail = auditTrail;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    MemberPage list(long gymId, String query, MemberStatus status, Long groupId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) badRequest("INVALID_PAGE_REQUEST");
        if (status == MemberStatus.ARCHIVED || status == MemberStatus.DELETION_REQUESTED || status == MemberStatus.DELETED) {
            badRequest("HIDDEN_MEMBER_STATUS_NOT_SEARCHABLE");
        }
        var trimmedQuery = blankToNull(query);
        var normalizedQuery = normalizePhone(trimmedQuery);
        if (normalizedQuery == null) normalizedQuery = "__NO_PHONE_MATCH__";
        var result = members.searchVisible(gymId, trimmedQuery, normalizedQuery, status, groupId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        var groupNames = groups.findAllByGymIdOrderByDisplayOrderAscNameAscIdAsc(gymId).stream()
                .collect(java.util.stream.Collectors.toMap(MemberGroupEntity::getId, MemberGroupEntity::getName));
        return new MemberPage(result.getContent().stream().map(member -> toSummary(member, groupNames)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    List<DuplicateCandidate> duplicates(long gymId, String name, LocalDate birthDate, String phone) {
        var normalizedPhone = normalizePhone(phone);
        return members.findDuplicateCandidates(gymId, name.trim(), birthDate, normalizedPhone).stream()
                .map(member -> new DuplicateCandidate(member.getId(), member.getMemberNumber(), member.getName(),
                        member.getBirthDate(), maskPhone(member.getPhone()), member.getStatus(),
                        member.getStatus() == MemberStatus.ARCHIVED))
                .toList();
    }

    @Transactional(readOnly = true)
    MemberDetail get(long gymId, long memberId) {
        var member = members.findByIdAndGymId(memberId, gymId).orElseThrow(MemberService::notFound);
        return toDetail(member);
    }

    @Transactional
    MemberDetail create(long gymId, long actorAccountId, MemberCommand command) {
        validate(command, true);
        assertActiveGroup(gymId, command.groupId());
        var duplicateCandidates = members.findDuplicateCandidates(
                gymId, command.name().trim(), command.birthDate(), normalizePhone(command.phone()));
        if (!duplicateCandidates.isEmpty() && !command.duplicateConfirmed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MEMBER_DUPLICATE_CONFIRMATION_REQUIRED");
        }

        var now = clock.instant();
        var number = nextMemberNumber(gymId, command.registeredOn(), now);
        var member = members.save(MemberEntity.create(
                gymId, command.groupId(), number, command.status(), command.name().trim(), clean(command.phone()),
                normalizePhone(command.phone()), command.birthDate(), command.gender(), clean(command.address()),
                clean(command.addressDetail()), clean(command.emergencyContactName()),
                clean(command.emergencyContactPhone()), clean(command.healthNotes()), command.registeredOn(),
                actorAccountId, now));
        statusHistory.save(MemberStatusHistoryEntity.create(
                member.getId(), null, member.getStatus(), now, "신규 등록", actorAccountId, now));
        groupHistory.save(MemberGroupHistoryEntity.create(
                member.getId(), null, member.getMemberGroupId(), command.registeredOn(), "신규 등록",
                actorAccountId, now));
        var guardianId = saveGuardian(gymId, member.getId(), actorAccountId, command.guardian(), now);
        saveConsents(member.getId(), guardianId, actorAccountId, command, now);
        if (clean(command.adminNote()) != null) {
            notes.save(MemberNoteEntity.create(
                    member.getId(), MemberNoteType.ADMIN, command.adminNote().trim(), actorAccountId, now));
        }
        auditTrail.record(gymId, actorAccountId, "MEMBER", "MEMBER_CREATED", "MEMBER",
                Long.toString(member.getId()), null, null, auditValues(member), now);
        return toDetail(member);
    }

    @Transactional
    MemberDetail update(long gymId, long actorAccountId, long memberId, MemberCommand command) {
        validate(command, false);
        var member = members.findForUpdateByIdAndGymId(memberId, gymId).orElseThrow(MemberService::notFound);
        if (!member.getRegisteredOn().equals(command.registeredOn())) badRequest("REGISTERED_ON_IMMUTABLE");
        if (command.status() == MemberStatus.DELETION_REQUESTED || command.status() == MemberStatus.DELETED) {
            badRequest("DELETION_STATUS_REQUIRES_DEDICATED_WORKFLOW");
        }
        if (!Objects.equals(member.getMemberGroupId(), command.groupId())) assertActiveGroup(gymId, command.groupId());
        var statusChanged = member.getStatus() != command.status();
        var groupChanged = !Objects.equals(member.getMemberGroupId(), command.groupId());
        if ((statusChanged || groupChanged) && clean(command.changeReason()) == null) {
            badRequest("CHANGE_REASON_REQUIRED");
        }
        var before = auditValues(member);
        var oldStatus = member.getStatus();
        var oldGroupId = member.getMemberGroupId();
        var now = clock.instant();
        member.update(command.groupId(), command.status(), command.name().trim(), clean(command.phone()),
                normalizePhone(command.phone()), command.birthDate(), command.gender(), clean(command.address()),
                clean(command.addressDetail()), clean(command.emergencyContactName()),
                clean(command.emergencyContactPhone()), clean(command.healthNotes()), actorAccountId, now);
        if (statusChanged) {
            statusHistory.save(MemberStatusHistoryEntity.create(memberId, oldStatus, command.status(), now,
                    command.changeReason().trim(), actorAccountId, now));
        }
        if (groupChanged) {
            groupHistory.save(MemberGroupHistoryEntity.create(memberId, oldGroupId, command.groupId(),
                    LocalDate.now(clock), command.changeReason().trim(), actorAccountId, now));
        }
        var guardianId = updateGuardian(gymId, memberId, actorAccountId, command.guardian(), now);
        saveChangedConsents(memberId, guardianId, actorAccountId, command, now);
        if (clean(command.adminNote()) != null) {
            notes.save(MemberNoteEntity.create(
                    memberId, MemberNoteType.ADMIN, command.adminNote().trim(), actorAccountId, now));
        }
        auditTrail.record(gymId, actorAccountId, "MEMBER", "MEMBER_UPDATED", "MEMBER",
                Long.toString(memberId), clean(command.changeReason()), before, auditValues(member), now);
        return toDetail(member);
    }

    private String nextMemberNumber(long gymId, LocalDate registeredOn, Instant now) {
        var yearMonth = registeredOn.format(YEAR_MONTH);
        var id = new MemberNumberSequenceId(gymId, yearMonth);
        sequences.ensureExists(gymId, yearMonth);
        var sequence = sequences.findForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("Member number sequence was not created"));
        var next = sequence.next(now);
        if (next > 9999) throw new ResponseStatusException(HttpStatus.CONFLICT, "MEMBER_NUMBER_EXHAUSTED");
        return "%s-%04d".formatted(yearMonth, next);
    }

    private void validate(MemberCommand command, boolean creating) {
        if (command.registeredOn().isAfter(LocalDate.now(clock))) badRequest("REGISTERED_ON_IN_FUTURE");
        if (command.birthDate().isAfter(command.registeredOn())) badRequest("BIRTH_DATE_AFTER_REGISTRATION");
        if (creating && command.status() != MemberStatus.ACTIVE && command.status() != MemberStatus.CONSULTING) {
            badRequest("INVALID_INITIAL_MEMBER_STATUS");
        }
        var adult = !command.birthDate().isAfter(command.registeredOn().minusYears(19));
        var normalizedPhone = normalizePhone(command.phone());
        if (adult && normalizedPhone == null) badRequest("ADULT_PHONE_REQUIRED");
        if (normalizedPhone != null && !validPhone(normalizedPhone)) badRequest("INVALID_MEMBER_PHONE");
        if (!adult && command.guardian() == null) badRequest("MINOR_GUARDIAN_REQUIRED");
        if (creating && !command.privacyConsent()) badRequest("PRIVACY_CONSENT_REQUIRED");
        if (command.guardian() != null) {
            var guardianPhone = normalizePhone(command.guardian().phone());
            if (guardianPhone == null) badRequest("GUARDIAN_PHONE_REQUIRED");
            if (!validPhone(guardianPhone)) badRequest("INVALID_GUARDIAN_PHONE");
        }
    }

    private void assertActiveGroup(long gymId, long groupId) {
        var group = groups.findByIdAndGymId(groupId, gymId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_GROUP_NOT_FOUND"));
        if (group.getStatus() != RecordStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MEMBER_GROUP_INACTIVE");
        }
    }

    private Long saveGuardian(
            long gymId, long memberId, long actorAccountId, GuardianCommand command, Instant now
    ) {
        if (command == null) return null;
        var guardian = guardians.save(GuardianEntity.create(gymId, command.name().trim(), command.phone().trim(),
                normalizePhone(command.phone()), clean(command.email()), actorAccountId, now));
        memberGuardians.save(MemberGuardianEntity.create(memberId, guardian.getId(), command.relationship(), true,
                command.receivesPaymentNotice(), command.receivesLessonNotice(), actorAccountId, now));
        return guardian.getId();
    }

    private Long updateGuardian(
            long gymId, long memberId, long actorAccountId, GuardianCommand command, Instant now
    ) {
        var links = memberGuardians.findAllForMember(memberId);
        var primaryLink = links.stream().filter(MemberGuardianEntity::isPrimary).findFirst().orElse(null);
        if (command == null) return primaryLink == null ? null : primaryLink.getId().getGuardianId();
        if (primaryLink == null) return saveGuardian(gymId, memberId, actorAccountId, command, now);
        var guardian = guardians.findById(primaryLink.getId().getGuardianId())
                .orElseThrow(() -> new IllegalStateException("Linked guardian was not found"));
        if (!Objects.equals(guardian.getGymId(), gymId)) throw notFound();
        guardian.update(command.name().trim(), command.phone().trim(), normalizePhone(command.phone()),
                clean(command.email()), actorAccountId, now);
        primaryLink.update(command.relationship(), true, command.receivesPaymentNotice(),
                command.receivesLessonNotice());
        return guardian.getId();
    }

    private void saveConsents(
            long memberId, Long guardianId, long actorAccountId, MemberCommand command, Instant now
    ) {
        consents.save(MemberConsentEntity.create(memberId, guardianId, ConsentType.PRIVACY,
                CONSENT_DOCUMENT_VERSION, command.privacyConsent(), now, actorAccountId, now));
        consents.save(MemberConsentEntity.create(memberId, guardianId, ConsentType.MARKETING_SMS,
                CONSENT_DOCUMENT_VERSION, command.marketingSmsConsent(), now, actorAccountId, now));
    }

    private void saveChangedConsents(
            long memberId, Long guardianId, long actorAccountId, MemberCommand command, Instant now
    ) {
        var history = consents.findAllByMemberIdOrderByDecidedAtDescIdDesc(memberId);
        saveConsentIfChanged(history, memberId, guardianId, actorAccountId, ConsentType.PRIVACY,
                command.privacyConsent(), now);
        saveConsentIfChanged(history, memberId, guardianId, actorAccountId, ConsentType.MARKETING_SMS,
                command.marketingSmsConsent(), now);
    }

    private void saveConsentIfChanged(
            List<MemberConsentEntity> history, long memberId, Long guardianId, long actorAccountId,
            ConsentType type, boolean agreed, Instant now
    ) {
        var latest = history.stream().filter(consent -> consent.getConsentType() == type).findFirst();
        if (latest.isPresent() && latest.get().isAgreed() == agreed) return;
        consents.save(MemberConsentEntity.create(memberId, guardianId, type, CONSENT_DOCUMENT_VERSION,
                agreed, now, actorAccountId, now));
    }

    private MemberDetail toDetail(MemberEntity member) {
        var group = groups.findByIdAndGymId(member.getMemberGroupId(), member.getGymId()).orElse(null);
        var guardianViews = memberGuardians.findAllForMember(member.getId()).stream()
                .map(link -> guardians.findById(link.getId().getGuardianId()).map(guardian -> new GuardianView(
                        guardian.getId(), guardian.getName(), guardian.getPhone(), guardian.getEmail(),
                        link.getRelationship(), link.isPrimary(), link.isReceivesPaymentNotice(),
                        link.isReceivesLessonNotice())).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        var consentViews = consents.findAllByMemberIdOrderByDecidedAtDescIdDesc(member.getId()).stream()
                .map(consent -> new ConsentView(consent.getConsentType(), consent.isAgreed(),
                        consent.getDocumentVersion(), consent.getDecidedAt()))
                .toList();
        var statusViews = statusHistory.findAllByMemberIdOrderByEffectiveAtDescIdDesc(member.getId()).stream()
                .map(history -> new StatusHistoryView(history.getFromStatus(), history.getToStatus(),
                        history.getEffectiveAt(), history.getReason())).toList();
        var groupViews = groupHistory.findAllByMemberIdOrderByEffectiveOnDescIdDesc(member.getId()).stream()
                .map(history -> new GroupHistoryView(history.getFromGroupId(), history.getToGroupId(),
                        history.getEffectiveOn(), history.getReason())).toList();
        return new MemberDetail(member.getId(), member.getMemberNumber(), member.getStatus(), member.getName(),
                member.getPhone(), member.getBirthDate(), member.getGender(), member.getMemberGroupId(),
                group == null ? null : group.getName(), member.getAddress(), member.getAddressDetail(),
                member.getEmergencyContactName(), member.getEmergencyContactPhone(), member.getHealthNotes(),
                member.getProfileImageKey(), member.getRegisteredOn(), member.getMembershipExpiredAt(),
                guardianViews, consentViews, statusViews, groupViews, member.getCreatedAt(), member.getUpdatedAt());
    }

    private static MemberSummary toSummary(MemberEntity member, Map<Long, String> groupNames) {
        return new MemberSummary(member.getId(), member.getMemberNumber(), member.getName(),
                maskPhone(member.getPhone()), member.getBirthDate(), member.getStatus(), member.getMemberGroupId(),
                groupNames.get(member.getMemberGroupId()), member.getMembershipExpiredAt(), member.getRegisteredOn());
    }

    private static Map<String, Object> auditValues(MemberEntity member) {
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("memberNumber", member.getMemberNumber());
        values.put("status", member.getStatus().name());
        values.put("memberGroupId", member.getMemberGroupId());
        values.put("registeredOn", member.getRegisteredOn().toString());
        return values;
    }

    static String normalizePhone(String value) {
        if (value == null) return null;
        var normalized = value.replaceAll("[^0-9]", "");
        return normalized.isBlank() ? null : normalized;
    }

    static String maskPhone(String value) {
        var normalized = normalizePhone(value);
        if (normalized == null || normalized.length() < 7) return value;
        var visibleTail = normalized.substring(normalized.length() - 4);
        return normalized.substring(0, 3) + "-****-" + visibleTail;
    }

    private static boolean validPhone(String normalizedPhone) {
        return normalizedPhone.length() >= 9 && normalizedPhone.length() <= 15;
    }

    private static String clean(String value) {
        return blankToNull(value);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static void badRequest(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND");
    }

    record MemberCommand(
            String name, String phone, LocalDate birthDate, Gender gender, long groupId, MemberStatus status,
            LocalDate registeredOn, String address, String addressDetail, String emergencyContactName,
            String emergencyContactPhone, String healthNotes, GuardianCommand guardian,
            boolean privacyConsent, boolean marketingSmsConsent, String adminNote,
            boolean duplicateConfirmed, String changeReason
    ) {
    }

    record GuardianCommand(
            String name, String phone, String email, GuardianRelationship relationship,
            boolean receivesPaymentNotice, boolean receivesLessonNotice
    ) {
    }

    record MemberPage(List<MemberSummary> content, int page, int size, long totalElements, int totalPages) {
    }

    record MemberSummary(
            long id, String memberNumber, String name, String maskedPhone, LocalDate birthDate,
            MemberStatus status, Long groupId, String groupName, Instant membershipExpiredAt, LocalDate registeredOn
    ) {
    }

    record DuplicateCandidate(
            long id, String memberNumber, String name, LocalDate birthDate, String maskedPhone,
            MemberStatus status, boolean reactivatable
    ) {
    }

    record MemberDetail(
            long id, String memberNumber, MemberStatus status, String name, String phone, LocalDate birthDate,
            Gender gender, Long groupId, String groupName, String address, String addressDetail,
            String emergencyContactName, String emergencyContactPhone, String healthNotes, String profileImageKey,
            LocalDate registeredOn, Instant membershipExpiredAt, List<GuardianView> guardians,
            List<ConsentView> consents, List<StatusHistoryView> statusHistory,
            List<GroupHistoryView> groupHistory, Instant createdAt, Instant updatedAt
    ) {
    }

    record GuardianView(
            long id, String name, String phone, String email, GuardianRelationship relationship,
            boolean primary, boolean receivesPaymentNotice, boolean receivesLessonNotice
    ) {
    }

    record ConsentView(ConsentType type, boolean agreed, String documentVersion, Instant decidedAt) {
    }

    record StatusHistoryView(MemberStatus fromStatus, MemberStatus toStatus, Instant effectiveAt, String reason) {
    }

    record GroupHistoryView(Long fromGroupId, Long toGroupId, LocalDate effectiveOn, String reason) {
    }
}

@RestController
@RequestMapping("/api/members")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('MEMBER_MANAGE')")
public class MemberManagementController {
    private final MemberService memberService;

    MemberManagementController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    public MemberService.MemberPage list(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return memberService.list(principal.gymId(), query, status, groupId, page, size);
    }

    @GetMapping("/duplicate-candidates")
    public List<MemberService.DuplicateCandidate> duplicateCandidates(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @RequestParam @NotBlank String name,
            @RequestParam @NotNull LocalDate birthDate,
            @RequestParam(required = false) String phone
    ) {
        return memberService.duplicates(principal.gymId(), name, birthDate, phone);
    }

    @GetMapping("/{memberId}")
    public MemberService.MemberDetail get(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long memberId
    ) {
        return memberService.get(principal.gymId(), memberId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberService.MemberDetail create(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @Valid @RequestBody MemberRequest request
    ) {
        return memberService.create(principal.gymId(), principal.accountId(), request.toCommand());
    }

    @PutMapping("/{memberId}")
    public MemberService.MemberDetail update(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long memberId,
            @Valid @RequestBody MemberRequest request
    ) {
        return memberService.update(principal.gymId(), principal.accountId(), memberId, request.toCommand());
    }

    public record MemberRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 30) String phone,
            @NotNull @PastOrPresent LocalDate birthDate,
            Gender gender,
            @NotNull Long groupId,
            @NotNull MemberStatus status,
            @NotNull @PastOrPresent LocalDate registeredOn,
            @Size(max = 300) String address,
            @Size(max = 300) String addressDetail,
            @Size(max = 100) String emergencyContactName,
            @Size(max = 30) String emergencyContactPhone,
            @Size(max = 2000) String healthNotes,
            @Valid GuardianRequest guardian,
            boolean privacyConsent,
            boolean marketingSmsConsent,
            @Size(max = 2000) String adminNote,
            boolean duplicateConfirmed,
            @Size(max = 500) String changeReason
    ) {
        MemberService.MemberCommand toCommand() {
            return new MemberService.MemberCommand(name, phone, birthDate, gender, groupId, status, registeredOn,
                    address, addressDetail, emergencyContactName, emergencyContactPhone, healthNotes,
                    guardian == null ? null : guardian.toCommand(), privacyConsent, marketingSmsConsent,
                    adminNote, duplicateConfirmed, changeReason);
        }
    }

    public record GuardianRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 30) String phone,
            @Size(max = 254) String email,
            @NotNull GuardianRelationship relationship,
            boolean receivesPaymentNotice,
            boolean receivesLessonNotice
    ) {
        MemberService.GuardianCommand toCommand() {
            return new MemberService.GuardianCommand(name, phone, email, relationship,
                    receivesPaymentNotice, receivesLessonNotice);
        }
    }
}
