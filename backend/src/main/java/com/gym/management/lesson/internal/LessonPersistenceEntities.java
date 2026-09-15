package com.gym.management.lesson.internal;

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
import java.time.LocalTime;

enum LessonType { GENERAL, PT }
enum LessonTemplateStatus { ACTIVE, INACTIVE }
enum PtAssignmentStatus { ACTIVE, INACTIVE }
enum LessonSessionStatus { SCHEDULED, COMPLETED, CANCELLED }

@Getter
@Entity
@Table(name = "lesson_templates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class LessonTemplateEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private String name;
    @Enumerated(EnumType.STRING) private LessonType lessonType;
    private Long instructorAccountId;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDate activeFrom;
    private LocalDate activeUntil;
    private String displayColor;
    @Enumerated(EnumType.STRING) private LessonTemplateStatus status;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "lesson_template_days")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class LessonTemplateDayEntity {
    @EmbeddedId
    private LessonTemplateDayId id;
}

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class LessonTemplateDayId implements Serializable {
    private Long lessonTemplateId;
    private short dayOfWeek;
}

@Getter
@Entity
@Table(name = "lesson_target_groups")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class LessonTargetGroupEntity {
    @EmbeddedId
    private LessonTargetGroupId id;
    private Instant createdAt;
    private Long createdBy;
}

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class LessonTargetGroupId implements Serializable {
    private Long lessonTemplateId;
    private Long memberGroupId;
}

@Getter
@Entity
@Table(name = "lesson_pt_assignments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class LessonPtAssignmentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long lessonTemplateId;
    private Long memberId;
    private Long membershipId;
    @Enumerated(EnumType.STRING) private PtAssignmentStatus status;
    private Instant assignedAt;
    private Instant endedAt;
    private Long createdBy;
}

@Getter
@Entity
@Table(name = "lesson_sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class LessonSessionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long lessonTemplateId;
    private Long gymId;
    private LocalDate sessionDate;
    private LocalTime startsAt;
    private LocalTime endsAt;
    @Enumerated(EnumType.STRING) private LessonSessionStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;
}
