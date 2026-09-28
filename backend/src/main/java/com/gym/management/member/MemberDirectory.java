package com.gym.management.member;

public interface MemberDirectory {
    IssuableMember requireIssuable(long gymId, long memberId);

    record IssuableMember(long id, String memberNumber, String name, String status) {
    }
}
