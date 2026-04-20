package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    @Query("select u from UserAccount u where lower(u.username) = lower(:username)")
    Optional<UserAccount> findByUsernameIgnoreCase(@Param("username") String username);

    @Query("select count(u) > 0 from UserAccount u where lower(u.username) = lower(:username)")
    boolean existsByUsernameIgnoreCase(@Param("username") String username);

    @Query("select count(u) > 0 from UserAccount u where u.email is not null and lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    boolean existsByPhone(String phone);

    @Query("""
            select u from UserAccount u
            where lower(u.username) = lower(:identifier)
               or (u.email is not null and lower(u.email) = lower(:identifier))
               or (u.phone is not null and u.phone = :identifier)
            """)
    Optional<UserAccount> findByIdentifier(@Param("identifier") String identifier);

    @Query("select u from UserAccount u where u.email is not null and lower(u.email) = lower(:email)")
    Optional<UserAccount> findByEmailIgnoreCase(@Param("email") String email);
}