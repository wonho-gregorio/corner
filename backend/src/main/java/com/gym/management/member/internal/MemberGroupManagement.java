package com.gym.management.member.internal;

import com.gym.management.auth.AuditTrail;
import com.gym.management.auth.AuthenticatedStaff;
import jakarta.persistence.LockModeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

interface MemberGroupRepository extends JpaRepository<MemberGroupEntity, Long> {
    List<MemberGroupEntity> findAllByGymIdOrderByDisplayOrderAscNameAscIdAsc(Long gymId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select memberGroup from MemberGroupEntity memberGroup where memberGroup.id = :groupId and memberGroup.gymId = :gymId")
    Optional<MemberGroupEntity> findForUpdateByIdAndGymId(
            @Param("groupId") long groupId,
            @Param("gymId") long gymId
    );

    @Query("""
            select (count(memberGroup) > 0) from MemberGroupEntity memberGroup
             where memberGroup.gymId = :gymId
               and lower(memberGroup.name) = lower(:name)
               and (:excludedId is null or memberGroup.id <> :excludedId)
            """)
    boolean existsDuplicateName(
            @Param("gymId") long gymId,
            @Param("name") String name,
            @Param("excludedId") Long excludedId
    );
}

@Service
class MemberGroupService {
    private final MemberGroupRepository groups;
    private final AuditTrail auditTrail;
    private final Clock clock;

    MemberGroupService(MemberGroupRepository groups, AuditTrail auditTrail) {
        this.groups = groups;
        this.auditTrail = auditTrail;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    List<MemberGroupView> list(long gymId) {
        return groups.findAllByGymIdOrderByDisplayOrderAscNameAscIdAsc(gymId).stream()
                .map(MemberGroupService::toView)
                .toList();
    }

    @Transactional
    MemberGroupView create(long gymId, long actorAccountId, MemberGroupCommand command) {
        assertNameAvailable(gymId, command.name(), null);
        var now = clock.instant();
        MemberGroupEntity group;
        try {
            group = groups.saveAndFlush(MemberGroupEntity.create(
                    gymId, command.name().trim(), command.displayOrder(), command.status(), actorAccountId, now));
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
        auditTrail.record(
                gymId, actorAccountId, "MEMBER", "MEMBER_GROUP_CREATED", "MEMBER_GROUP",
                Long.toString(group.getId()), null, null, auditValues(group), now);
        return toView(group);
    }

    @Transactional
    MemberGroupView update(long gymId, long actorAccountId, long groupId, MemberGroupCommand command) {
        var group = groups.findForUpdateByIdAndGymId(groupId, gymId)
                .orElseThrow(MemberGroupService::notFound);
        assertNameAvailable(gymId, command.name(), groupId);
        var before = auditValues(group);
        var now = clock.instant();
        group.update(command.name().trim(), command.displayOrder(), command.status(), actorAccountId, now);
        try {
            groups.flush();
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
        auditTrail.record(
                gymId, actorAccountId, "MEMBER", "MEMBER_GROUP_UPDATED", "MEMBER_GROUP",
                Long.toString(groupId), null, before, auditValues(group), now);
        return toView(group);
    }

    private void assertNameAvailable(long gymId, String name, Long excludedId) {
        if (groups.existsDuplicateName(gymId, name.trim(), excludedId)) {
            throw duplicateName();
        }
    }

    private static Map<String, Object> auditValues(MemberGroupEntity group) {
        return Map.of(
                "name", group.getName(),
                "displayOrder", group.getDisplayOrder(),
                "status", group.getStatus().name());
    }

    private static MemberGroupView toView(MemberGroupEntity group) {
        return new MemberGroupView(
                group.getId(), group.getName(), group.getDisplayOrder(), group.getStatus(), group.getCreatedAt(),
                group.getUpdatedAt());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_GROUP_NOT_FOUND");
    }

    private static ResponseStatusException duplicateName() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "MEMBER_GROUP_NAME_ALREADY_EXISTS");
    }

    record MemberGroupCommand(String name, int displayOrder, RecordStatus status) {
    }

    record MemberGroupView(
            long id,
            String name,
            int displayOrder,
            RecordStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}

@RestController
@RequestMapping("/api/member-groups")
@PreAuthorize("hasRole('ADMIN')")
public class MemberGroupManagement {
    private final MemberGroupService memberGroupService;

    MemberGroupManagement(MemberGroupService memberGroupService) {
        this.memberGroupService = memberGroupService;
    }

    @GetMapping
    public List<MemberGroupService.MemberGroupView> list(@AuthenticationPrincipal AuthenticatedStaff principal) {
        return memberGroupService.list(principal.gymId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberGroupService.MemberGroupView create(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @Valid @RequestBody MemberGroupRequest request
    ) {
        return memberGroupService.create(principal.gymId(), principal.accountId(), request.toCommand());
    }

    @PutMapping("/{groupId}")
    public MemberGroupService.MemberGroupView update(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long groupId,
            @Valid @RequestBody MemberGroupRequest request
    ) {
        return memberGroupService.update(
                principal.gymId(), principal.accountId(), groupId, request.toCommand());
    }

    public record MemberGroupRequest(
            @NotBlank @Size(max = 100) String name,
            @PositiveOrZero int displayOrder,
            @NotNull RecordStatus status
    ) {
        MemberGroupService.MemberGroupCommand toCommand() {
            return new MemberGroupService.MemberGroupCommand(name, displayOrder, status);
        }
    }
}
