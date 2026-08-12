import type { PropsWithChildren } from 'react';
import { useLaunch } from '@tarojs/taro';
import { QueryProvider } from '@ep/query';
import { ErrorBoundary } from '@ep/ui-kit';
import './app.scss';

function App({ children }: PropsWithChildren) {
  useLaunch(() => {
    // C-11: Check privacy agreement on launch
    console.log('EP Student MP launched');
  });

  return (
    <ErrorBoundary>
      <QueryProvider>{children}</QueryProvider>
    </ErrorBoundary>
  );
}

export default App;
