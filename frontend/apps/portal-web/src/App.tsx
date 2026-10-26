import { Routes, Route } from 'react-router-dom';
import { Suspense, lazy, type ReactNode } from 'react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryProvider } from '@ep/query';
import { ThemeProvider, useTheme } from '@ep/ui-kit';
import PortalLayout from './layouts/PortalLayout';
// D-235: route-level code splitting — only the landing page stays eager for
// fastest FCP; every other route is a separate chunk fetched on navigation.
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import { antdThemeConfig } from './theme/antdTheme';

const CourseCatalogPage = lazy(() => import('./pages/CourseCatalogPage'));
const CourseDetailPage = lazy(() => import('./pages/CourseDetailPage'));
const AboutPage = lazy(() => import('./pages/AboutPage'));
const BusinessUnitsPage = lazy(() => import('./pages/BusinessUnitsPage'));
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'));

/**
 * D-235: minimal route fallback shown while a lazily loaded page chunk is
 * downloading. Kept intentionally light — it should paint instantly.
 */
function PageFallback() {
  return (
    <div style={{ minHeight: '40vh', display: 'grid', placeItems: 'center' }} aria-busy="true">
      <span>加载中…</span>
    </div>
  );
}

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
          <Suspense fallback={<PageFallback />}>
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
          </Suspense>
        </QueryProvider>
      </ThemedConfigProvider>
    </ThemeProvider>
  );
}
