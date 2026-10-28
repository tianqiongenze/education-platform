@integration
Feature: 课程生命周期
  教师创建课程、发布课程供学生报名。
  (措辞与 BusinessFakes.FakeCourseService 的真实 API 对齐:
   课程创建时处于 DRAFT, publishCourse 后变为 PUBLISHED, 价格随创建命令传入)

  Scenario: 教师发布课程供学生报名
    Given 教师 "t1" 已登录系统
    When 教师创建课程 "Java进阶" 定价 99.0
    And 教师发布该课程
    Then 课程状态为 PUBLISHED
