package com.ep.business.course;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CourseRepository extends JpaRepository<Course, Long> {

    Page<Course> findAllByOrderByCourseIdAsc(Pageable pageable);

    List<Course> findByTitleContainingOrderByCourseIdAsc(String keyword);
}
