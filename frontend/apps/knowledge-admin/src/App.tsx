import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';
import { QueryProvider } from '@ep/query';
import { ErrorBoundary, ThemeProvider, useTheme } from '@ep/ui-kit';
import { AppRoutes } from './routes';
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
    <ErrorBoundary>
      <ThemeProvider>
        <ThemedConfigProvider>
          <BrowserRouter>
            <QueryProvider>
              <AppRoutes />
            </QueryProvider>
          </BrowserRouter>
        </ThemedConfigProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
}
