import { Routes, Route } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';
import { QueryProvider } from '@ep/query';
import { ThemeProvider, useTheme } from '@ep/ui-kit';
import PortalLayout from './layouts/PortalLayout';
import HomePage from './pages/HomePage';
import CourseCatalogPage from './pages/CourseCatalogPage';
import CourseDetailPage from './pages/CourseDetailPage';
import AboutPage from './pages/AboutPage';
import BusinessUnitsPage from './pages/BusinessUnitsPage';
import LoginPage from './pages/LoginPage';
import NotFoundPage from './pages/NotFoundPage';
import { antdThemeConfig } from './theme/antdTheme';

/**
 * Bridges the ui-kit ThemeProvider (CSS variables + persisted preference) to
 * AntD 5's ConfigProvider so both design systems follow the same mode.
 */
function ThemedConfigProvider({ children }: { children: ReactNode }) {
  const { theme } = useTheme();
  return (
    <ConfigProvider locale={zhCN} theme={antdThemeConfig(theme)}>
      {children}
    </ConfigProvider>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <ThemedConfigProvider>
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
      </ThemedConfigProvider>
    </ThemeProvider>
  );
}
