package com.forgeddetect.demo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoStoryRepository extends JpaRepository<DemoStory, Long> {

    Optional<DemoStory> findTopByOrderByIdDesc();
}
