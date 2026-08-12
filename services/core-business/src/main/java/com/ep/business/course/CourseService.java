package com.ep.business.course;

import java.util.List;

public interface CourseService {
    Course createCourse(CreateCourseCmd cmd);
    Course getCourseById(Long courseId);
    List<Course> listCourses(Integer page, Integer size);
    List<Course> searchCourses(String keyword);
    void publishCourse(Long courseId);
    void archiveCourse(Long courseId);
}
