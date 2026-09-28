package com.gym.management.member.internal;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

enum RecordStatus { ACTIVE, INACTIVE }
enum MemberStatus { ACTIVE, CONSULTING, EXPIRED, ARCHIVED, DELETION_REQUESTED, DELETED }
enum Gender { MALE, FEMALE, OTHER, UNSPECIFIED }
enum GuardianRelationship { PARENT, GRANDPARENT, SIBLING, SPOUSE, OTHER }
enum FamilyRelationship { PARENT, CHILD, SIBLING, SPOUSE, OTHER }
enum ConsentType { PRIVACY, MARKETING_SMS, KAKAO_NOTIFICATION, MINOR_GUARDIAN }
enum MemberNoteType { CONSULTATION, ADMIN }

@Getter
@Entity
@Table(name = "member_number_sequences")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberNumberSequenceEntity {
    @EmbeddedId
    private MemberNumberSequenceId id;
    private int lastValue;
    private Instant updatedAt;

    int next(Instant now) {
        this.lastValue += 1;
        this.updatedAt = now;
        return this.lastValue;
    }
}

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberNumberSequenceId implements Serializable {
    private Long gymId;
    private String yearMonth;

    MemberNumberSequenceId(Long gymId, String yearMonth) {
        this.gymId = gymId;
        this.yearMonth = yearMonth;
    }
}

@Getter
@Entity
@Table(name = "member_groups")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberGroupEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private String name;
    private int displayOrder;
    @Enumerated(EnumType.STRING) private RecordStatus status;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;

    static MemberGroupEntity create(
            long gymId,
            String name,
            int displayOrder,
            RecordStatus status,
            long createdBy,
            Instant now
    ) {
        var group = new MemberGroupEntity();
        group.gymId = gymId;
        group.name = name;
        group.displayOrder = displayOrder;
        group.status = status;
        group.createdAt = now;
        group.createdBy = createdBy;
        group.updatedAt = now;
        group.updatedBy = createdBy;
        return group;
    }

    void update(String name, int displayOrder, RecordStatus status, long updatedBy, Instant now) {
        this.name = name;
        this.displayOrder = displayOrder;
        this.status = status;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }
}

@Getter
@Entity
@Table(name = "members")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private Long memberGroupId;
    private String memberNumber;
    @Enumerated(EnumType.STRING) private MemberStatus status;
    private String name;
    private String phone;
    private String normalizedPhone;
    private LocalDate birthDate;
    @Enumerated(EnumType.STRING) private Gender gender;
    private String address;
    private String addressDetail;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String healthNotes;
    private String profileImageKey;
    private LocalDate registeredOn;
    private Instant membershipExpiredAt;
    private Instant archivedAt;
    private Instant deletionRequestedAt;
    private Instant deletedAt;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;

    static MemberEntity create(
            long gymId, Long memberGroupId, String memberNumber, MemberStatus status, String name,
            String phone, String normalizedPhone, LocalDate birthDate, Gender gender, String address,
            String addressDetail, String emergencyContactName, String emergencyContactPhone,
            String healthNotes, LocalDate registeredOn, long actorAccountId, Instant now
    ) {
        var member = new MemberEntity();
        member.gymId = gymId;
        member.memberGroupId = memberGroupId;
        member.memberNumber = memberNumber;
        member.status = status;
        member.name = name;
        member.phone = phone;
        member.normalizedPhone = normalizedPhone;
        member.birthDate = birthDate;
        member.gender = gender;
        member.address = address;
        member.addressDetail = addressDetail;
        member.emergencyContactName = emergencyContactName;
        member.emergencyContactPhone = emergencyContactPhone;
        member.healthNotes = healthNotes;
        member.registeredOn = registeredOn;
        member.createdAt = now;
        member.createdBy = actorAccountId;
        member.updatedAt = now;
        member.updatedBy = actorAccountId;
        member.applyLifecycleTimestamps(null, status, now);
        return member;
    }

    void update(
            Long memberGroupId, MemberStatus status, String name, String phone, String normalizedPhone,
            LocalDate birthDate, Gender gender, String address, String addressDetail,
            String emergencyContactName, String emergencyContactPhone, String healthNotes,
            long actorAccountId, Instant now
    ) {
        var previousStatus = this.status;
        this.memberGroupId = memberGroupId;
        this.status = status;
        this.name = name;
        this.phone = phone;
        this.normalizedPhone = normalizedPhone;
        this.birthDate = birthDate;
        this.gender = gender;
        this.address = address;
        this.addressDetail = addressDetail;
        this.emergencyContactName = emergencyContactName;
        this.emergencyContactPhone = emergencyContactPhone;
        this.healthNotes = healthNotes;
        this.updatedAt = now;
        this.updatedBy = actorAccountId;
        applyLifecycleTimestamps(previousStatus, status, now);
    }

    private void applyLifecycleTimestamps(MemberStatus previousStatus, MemberStatus newStatus, Instant now) {
        if (newStatus == MemberStatus.ARCHIVED && previousStatus != MemberStatus.ARCHIVED) archivedAt = now;
        if (newStatus == MemberStatus.DELETION_REQUESTED && previousStatus != MemberStatus.DELETION_REQUESTED) {
            deletionRequestedAt = now;
        }
        if (newStatus == MemberStatus.DELETED && previousStatus != MemberStatus.DELETED) deletedAt = now;
        if (newStatus != MemberStatus.ARCHIVED) archivedAt = null;
        if (newStatus != MemberStatus.DELETION_REQUESTED && newStatus != MemberStatus.DELETED) {
            deletionRequestedAt = null;
        }
        if (newStatus != MemberStatus.DELETED) deletedAt = null;
    }
}

@Getter
@Entity
@Table(name = "member_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberStatusHistoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long memberId;
    @Enumerated(EnumType.STRING) private MemberStatus fromStatus;
    @Enumerated(EnumType.STRING) private MemberStatus toStatus;
    private Instant effectiveAt;
    private String reason;
    private Long changedBy;
    private Instant createdAt;

    static MemberStatusHistoryEntity create(
            long memberId, MemberStatus fromStatus, MemberStatus toStatus, Instant effectiveAt,
            String reason, long changedBy, Instant now
    ) {
        var history = new MemberStatusHistoryEntity();
        history.memberId = memberId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.effectiveAt = effectiveAt;
        history.reason = reason;
        history.changedBy = changedBy;
        history.createdAt = now;
        return history;
    }
}

@Getter
@Entity
@Table(name = "member_group_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberGroupHistoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long memberId;
    private Long fromGroupId;
    private Long toGroupId;
    private LocalDate effectiveOn;
    private String reason;
    private Long changedBy;
    private Instant createdAt;

    static MemberGroupHistoryEntity create(
            long memberId, Long fromGroupId, Long toGroupId, LocalDate effectiveOn,
            String reason, long changedBy, Instant now
    ) {
        var history = new MemberGroupHistoryEntity();
        history.memberId = memberId;
        history.fromGroupId = fromGroupId;
        history.toGroupId = toGroupId;
        history.effectiveOn = effectiveOn;
        history.reason = reason;
        history.changedBy = changedBy;
        history.createdAt = now;
        return history;
    }
}

@Getter
@Entity
@Table(name = "guardians")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class GuardianEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private String name;
    private String phone;
    private String normalizedPhone;
    private String email;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;

    static GuardianEntity create(
            long gymId, String name, String phone, String normalizedPhone, String email,
            long actorAccountId, Instant now
    ) {
        var guardian = new GuardianEntity();
        guardian.gymId = gymId;
        guardian.name = name;
        guardian.phone = phone;
        guardian.normalizedPhone = normalizedPhone;
        guardian.email = email;
        guardian.createdAt = now;
        guardian.createdBy = actorAccountId;
        guardian.updatedAt = now;
        guardian.updatedBy = actorAccountId;
        return guardian;
    }

    void update(
            String name, String phone, String normalizedPhone, String email, long actorAccountId, Instant now
    ) {
        this.name = name;
        this.phone = phone;
        this.normalizedPhone = normalizedPhone;
        this.email = email;
        this.updatedAt = now;
        this.updatedBy = actorAccountId;
    }
}

@Getter
@Entity
@Table(name = "member_guardians")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberGuardianEntity {
    @EmbeddedId
    private MemberGuardianId id;
    @Enumerated(EnumType.STRING) private GuardianRelationship relationship;
    private boolean isPrimary;
    private boolean receivesPaymentNotice;
    private boolean receivesLessonNotice;
    private Instant createdAt;
    private Long createdBy;

    static MemberGuardianEntity create(
            long memberId, long guardianId, GuardianRelationship relationship, boolean primary,
            boolean receivesPaymentNotice, boolean receivesLessonNotice, long actorAccountId, Instant now
    ) {
        var link = new MemberGuardianEntity();
        link.id = new MemberGuardianId(memberId, guardianId);
        link.relationship = relationship;
        link.isPrimary = primary;
        link.receivesPaymentNotice = receivesPaymentNotice;
        link.receivesLessonNotice = receivesLessonNotice;
        link.createdAt = now;
        link.createdBy = actorAccountId;
        return link;
    }

    void update(
            GuardianRelationship relationship, boolean primary,
            boolean receivesPaymentNotice, boolean receivesLessonNotice
    ) {
        this.relationship = relationship;
        this.isPrimary = primary;
        this.receivesPaymentNotice = receivesPaymentNotice;
        this.receivesLessonNotice = receivesLessonNotice;
    }
}

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberGuardianId implements Serializable {
    private Long memberId;
    private Long guardianId;

    MemberGuardianId(Long memberId, Long guardianId) {
        this.memberId = memberId;
        this.guardianId = guardianId;
    }
}

@Getter
@Entity
@Table(name = "member_relationships")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberRelationshipEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private Long lowerMemberId;
    private Long higherMemberId;
    @Enumerated(EnumType.STRING) private FamilyRelationship lowerToHigherType;
    @Enumerated(EnumType.STRING) private FamilyRelationship higherToLowerType;
    private Instant createdAt;
    private Long createdBy;
}

@Getter
@Entity
@Table(name = "member_consents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberConsentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long memberId;
    private Long guardianId;
    @Enumerated(EnumType.STRING) private ConsentType consentType;
    private String documentVersion;
    private boolean agreed;
    private Instant decidedAt;
    private Long recordedBy;
    private Instant createdAt;

    static MemberConsentEntity create(
            long memberId, Long guardianId, ConsentType consentType, String documentVersion,
            boolean agreed, Instant decidedAt, long recordedBy, Instant now
    ) {
        var consent = new MemberConsentEntity();
        consent.memberId = memberId;
        consent.guardianId = guardianId;
        consent.consentType = consentType;
        consent.documentVersion = documentVersion;
        consent.agreed = agreed;
        consent.decidedAt = decidedAt;
        consent.recordedBy = recordedBy;
        consent.createdAt = now;
        return consent;
    }
}

@Getter
@Entity
@Table(name = "member_notes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberNoteEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long memberId;
    @Enumerated(EnumType.STRING) private MemberNoteType noteType;
    private String content;
    private Long createdBy;
    private Instant createdAt;

    static MemberNoteEntity create(long memberId, MemberNoteType noteType, String content, long createdBy, Instant now) {
        var note = new MemberNoteEntity();
        note.memberId = memberId;
        note.noteType = noteType;
        note.content = content;
        note.createdBy = createdBy;
        note.createdAt = now;
        return note;
    }
}
