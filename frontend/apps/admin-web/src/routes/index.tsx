import { Routes, Route, Navigate } from 'react-router-dom';
import { Suspense, lazy, type ComponentType } from 'react';
import { AuthGuard } from './AuthGuard';
import AdminLayout from '../layouts/AdminLayout';
// D-235: route-level code splitting. The login page and the post-login
// landing dashboard stay eager (they are the two above-the-fold screens);
// every other admin page is an on-demand chunk — a 20+ page admin SPA used
// to ship one monolithic route bundle.
import LoginPage from '../pages/Login';
import DashboardPage from '../pages/Dashboard';

const CourseListPage = lazy(() => import('../pages/CourseMgmt/List'));
const CourseDetailPage = lazy(() => import('../pages/CourseMgmt/Detail'));
const AssignmentMgmtPage = lazy(() => import('../pages/AssignmentMgmt'));
const StudentMgmtPage = lazy(() => import('../pages/StudentMgmt'));
const TeacherMgmtPage = lazy(() => import('../pages/TeacherMgmt'));
const LiveMgmtPage = lazy(() => import('../pages/LiveMgmt'));
const CodingMgmtPage = lazy(() => import('../pages/CodingMgmt'));
const GradeMgmtPage = lazy(() => import('../pages/GradeMgmt'));
const SystemSettingsPage = lazy(() => import('../pages/SystemSettings'));
const SlaDashboardPage = lazy(() => import('../pages/SlaDashboard'));
const ForbiddenPage = lazy(() => import('../pages/Forbidden'));
const NotFoundPage = lazy(() => import('../pages/NotFound'));

/**
 * D-235: lazy wrapper for named exports (barrel modules like it-backend /
 * business re-export several pages per file) — one chunk per barrel keeps
 * request count sane while still splitting the big route families apart.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function lazyNamed(loader: () => Promise<Record<string, ComponentType<any>>>, key: string): ComponentType<any> {
  return lazy(async () => {
    const mod = await loader();
    const Component = mod[key];
    if (!Component) {
      // A missing named export is a build-time programming error — fail loud
      // in dev, show the fallback in prod rather than a white screen.
      throw new Error(`[routes] named export "${String(key)}" not found in lazy barrel module`);
    }
    return { default: Component };
  });
}

const AssignmentListPage = lazyNamed(() => import('../pages/it-backend'), 'AssignmentListPage');
const AssignmentCreatePage = lazyNamed(() => import('../pages/it-backend'), 'AssignmentCreatePage');
const AssignmentDetailPage = lazyNamed(() => import('../pages/it-backend'), 'AssignmentDetailPage');
const SubmissionListPage = lazyNamed(() => import('../pages/it-backend'), 'SubmissionListPage');
const GradeSubmissionPage = lazyNamed(() => import('../pages/it-backend'), 'GradeSubmissionPage');
const GradingPipelinePage = lazyNamed(() => import('../pages/it-backend'), 'GradingPipelinePage');

const UserListPage = lazyNamed(() => import('../pages/business'), 'UserListPage');
const UserCreatePage = lazyNamed(() => import('../pages/business'), 'UserCreatePage');
const UserDetailPage = lazyNamed(() => import('../pages/business'), 'UserDetailPage');
const BusinessCourseListPage = lazyNamed(() => import('../pages/business'), 'CourseListPage');
const CourseCreatePage = lazyNamed(() => import('../pages/business'), 'CourseCreatePage');
const BusinessCourseDetailPage = lazyNamed(() => import('../pages/business'), 'CourseDetailPage');
const OrderListPage = lazyNamed(() => import('../pages/business'), 'OrderListPage');
const OrderDetailPage = lazyNamed(() => import('../pages/business'), 'OrderDetailPage');

/** Minimal instant-paint fallback while a route chunk downloads. */
function PageFallback() {
  return (
    <div style={{ minHeight: '40vh', display: 'grid', placeItems: 'center' }} aria-busy="true">
      <span>加载中…</span>
    </div>
  );
}

export function AppRoutes() {
  return (
    <Suspense fallback={<PageFallback />}>
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
          <Route path="sla" element={<SlaDashboardPage />} />
          <Route path="403" element={<ForbiddenPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </Suspense>
  );
}

export default AppRoutes;
