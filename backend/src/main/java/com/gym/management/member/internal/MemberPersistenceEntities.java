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
}

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberNumberSequenceId implements Serializable {
    private Long gymId;
    private String yearMonth;
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
}

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemberGuardianId implements Serializable {
    private Long memberId;
    private Long guardianId;
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
}
