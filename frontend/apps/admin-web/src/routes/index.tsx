import { Routes, Route, Navigate } from 'react-router-dom';
import { AuthGuard } from './AuthGuard';
import AdminLayout from '../layouts/AdminLayout';
import LoginPage from '../pages/Login';
import DashboardPage from '../pages/Dashboard';
import CourseListPage from '../pages/CourseMgmt/List';
import CourseDetailPage from '../pages/CourseMgmt/Detail';
import AssignmentMgmtPage from '../pages/AssignmentMgmt';
import StudentMgmtPage from '../pages/StudentMgmt';
import TeacherMgmtPage from '../pages/TeacherMgmt';
import LiveMgmtPage from '../pages/LiveMgmt';
import CodingMgmtPage from '../pages/CodingMgmt';
import GradeMgmtPage from '../pages/GradeMgmt';
import SystemSettingsPage from '../pages/SystemSettings';
import ForbiddenPage from '../pages/Forbidden';
import NotFoundPage from '../pages/NotFound';
import {
  AssignmentListPage,
  AssignmentCreatePage,
  AssignmentDetailPage,
  SubmissionListPage,
  GradeSubmissionPage,
  GradingPipelinePage,
} from '../pages/it-backend';
import {
  UserListPage,
  UserCreatePage,
  UserDetailPage,
  CourseListPage as BusinessCourseListPage,
  CourseCreatePage,
  CourseDetailPage as BusinessCourseDetailPage,
  OrderListPage,
  OrderDetailPage,
} from '../pages/business';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <AuthGuard>
            <AdminLayout />
          </AuthGuard>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="courses" element={<CourseListPage />} />
        <Route path="courses/:id" element={<CourseDetailPage />} />
        <Route path="assignments" element={<AssignmentMgmtPage />} />
        <Route path="students" element={<StudentMgmtPage />} />
        <Route path="teachers" element={<TeacherMgmtPage />} />
        <Route path="live" element={<LiveMgmtPage />} />
        <Route path="coding" element={<CodingMgmtPage />} />
        <Route path="grades" element={<GradeMgmtPage />} />
        <Route path="it-backend/assignments" element={<AssignmentListPage />} />
        <Route path="it-backend/assignments/create" element={<AssignmentCreatePage />} />
        <Route path="it-backend/assignments/:id" element={<AssignmentDetailPage />} />
        <Route path="it-backend/submissions" element={<SubmissionListPage />} />
        <Route
          path="it-backend/grading/:submissionId"
          element={<GradingPipelinePage />}
        />
        <Route
          path="it-backend/grading/:submissionId/grade"
          element={<GradeSubmissionPage />}
        />
        <Route path="business/users" element={<UserListPage />} />
        <Route path="business/users/create" element={<UserCreatePage />} />
        <Route path="business/users/:id" element={<UserDetailPage />} />
        <Route path="business/courses" element={<BusinessCourseListPage />} />
        <Route path="business/courses/create" element={<CourseCreatePage />} />
        <Route path="business/courses/:id" element={<BusinessCourseDetailPage />} />
        <Route path="business/orders" element={<OrderListPage />} />
        <Route path="business/orders/:id" element={<OrderDetailPage />} />
        <Route path="settings" element={<SystemSettingsPage />} />
        <Route path="403" element={<ForbiddenPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

export default AppRoutes;
