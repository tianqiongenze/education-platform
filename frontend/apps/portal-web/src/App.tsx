import { Routes, Route } from 'react-router-dom';
import { QueryProvider } from '@ep/query';
import PortalLayout from './layouts/PortalLayout';
import HomePage from './pages/HomePage';
import CourseCatalogPage from './pages/CourseCatalogPage';
import CourseDetailPage from './pages/CourseDetailPage';
import AboutPage from './pages/AboutPage';
import BusinessUnitsPage from './pages/BusinessUnitsPage';
import LoginPage from './pages/LoginPage';
import NotFoundPage from './pages/NotFoundPage';

export default function App() {
  return (
    <QueryProvider>
      <Routes>
        <Route element={<PortalLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/courses" element={<CourseCatalogPage />} />
          <Route path="/courses/:courseId" element={<CourseDetailPage />} />
          <Route path="/about" element={<AboutPage />} />
          <Route path="/bu" element={<BusinessUnitsPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </QueryProvider>
  );
}
