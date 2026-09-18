import { Component, type ReactNode } from 'react';
import { View } from '../../platform';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}
interface State {
  hasError: boolean;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: { componentStack: string }) {
    // C-10: error reporting must NOT contain PII.
    // Only log the error message and component stack; never props/state.
    console.error('[ErrorBoundary]', error.message, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return (
        this.props.fallback ?? (
          // role="alert" so screen readers announce the failure immediately.
          <View className="ep-error-boundary" role="alert">
            页面出错了
          </View>
        )
      );
    }
    return this.props.children;
  }
}
