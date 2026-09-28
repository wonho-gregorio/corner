package com.gym.management.member.internal;

import com.gym.management.member.MemberDirectory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

interface IssuableMemberRepository extends org.springframework.data.repository.Repository<MemberEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from MemberEntity member where member.id = :memberId and member.gymId = :gymId")
    Optional<MemberEntity> findForIssuance(@Param("gymId") long gymId, @Param("memberId") long memberId);
}

@Service
class MemberDirectoryService implements MemberDirectory {
    private final IssuableMemberRepository members;

    MemberDirectoryService(IssuableMemberRepository members) {
        this.members = members;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuableMember requireIssuable(long gymId, long memberId) {
        var member = members.findForIssuance(gymId, memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));
        if (member.getStatus() == MemberStatus.ARCHIVED
                || member.getStatus() == MemberStatus.DELETION_REQUESTED
                || member.getStatus() == MemberStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MEMBER_NOT_ISSUABLE");
        }
        return new IssuableMember(member.getId(), member.getMemberNumber(), member.getName(), member.getStatus().name());
    }
}
