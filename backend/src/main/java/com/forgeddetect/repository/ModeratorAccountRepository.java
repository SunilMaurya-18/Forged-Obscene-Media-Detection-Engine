package com.forgeddetect.repository;

import com.forgeddetect.model.ModeratorAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModeratorAccountRepository extends JpaRepository<ModeratorAccount, Long> {

    Optional<ModeratorAccount> findByUsername(String username);

    List<ModeratorAccount> findAllByOrderByTeamAscUsernameAsc();
}
