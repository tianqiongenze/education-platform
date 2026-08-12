import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryProvider } from '@ep/query';
import { ErrorBoundary } from '@ep/ui-kit';
import { AppRoutes } from './routes';
import { UserProvider } from './stores/UserContext';

export default function App() {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <UserProvider>
          <QueryProvider>
            <ConfigProvider
              locale={zhCN}
              theme={{
                token: {
                  colorPrimary: '#1890ff',
                  borderRadius: 6,
                },
                algorithm: antdTheme.defaultAlgorithm,
              }}
            >
              <AppRoutes />
            </ConfigProvider>
          </QueryProvider>
        </UserProvider>
      </BrowserRouter>
    </ErrorBoundary>
  );
}
