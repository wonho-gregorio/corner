package com.gym.management.notification.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

enum NotificationProvider { SOLAPI }
enum NotificationRecordStatus { ACTIVE, INACTIVE }
enum NotificationJobType { REREGISTRATION, TEST }
enum NotificationMessageType { SMS, LMS }
enum NotificationJobStatus { PENDING, PROCESSING, SENT, FAILED, CANCELLED }
enum NotificationAttemptResult { SUCCESS, TEMPORARY_FAILURE, PERMANENT_FAILURE }

@Getter
@Entity
@Table(name = "notification_senders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class NotificationSenderEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    @Enumerated(EnumType.STRING) private NotificationProvider provider;
    private String providerSenderId;
    private String phone;
    private String normalizedPhone;
    @Enumerated(EnumType.STRING) private NotificationRecordStatus status;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "notification_test_recipients")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class NotificationTestRecipientEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private String name;
    private String phone;
    private String normalizedPhone;
    @Enumerated(EnumType.STRING) private NotificationRecordStatus status;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "notification_setting_versions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class NotificationSettingVersionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private int versionNo;
    private boolean enabled;
    private LocalTime sendTime;
    private String titleTemplate;
    private String bodyTemplate;
    private Long senderId;
    private Instant activeFrom;
    private Instant retiredAt;
    private Long createdBy;
    private Instant createdAt;
}

@Getter
@Entity
@Table(name = "notification_jobs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class NotificationJobEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private Long memberId;
    private Long membershipId;
    private Long testRecipientId;
    private Long settingVersionId;
    @Enumerated(EnumType.STRING) private NotificationJobType jobType;
    private LocalDate reregistrationDate;
    private String recipientName;
    private String recipientPhoneMasked;
    private String recipientPhoneCiphertext;
    private String recipientPhoneHash;
    private String titleSnapshot;
    private String bodySnapshot;
    @Enumerated(EnumType.STRING) private NotificationMessageType messageType;
    @Enumerated(EnumType.STRING) private NotificationJobStatus status;
    private Instant scheduledAt;
    private Instant nextAttemptAt;
    private int attemptCount;
    private Instant claimedAt;
    private String claimedBy;
    private Instant sentAt;
    private String failureCode;
    private Instant cancelledAt;
    private String cancellationReason;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "notification_attempts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class NotificationAttemptEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long jobId;
    private int attemptNo;
    private Instant requestedAt;
    private Instant completedAt;
    private String providerMessageId;
    @Enumerated(EnumType.STRING) private NotificationAttemptResult result;
    private String failureCode;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> providerResponse;
    private Instant createdAt;
}
