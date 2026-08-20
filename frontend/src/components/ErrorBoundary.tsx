import { Component, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Temporary but important on its own merits: without this, an uncaught render error anywhere
 * in the tree unmounts React entirely and leaves a blank white/black page with no clue why —
 * exactly the symptom being chased in a native-app test where there's no console to check.
 * Shows the actual error + stack directly on screen instead.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: { componentStack?: string | null }) {
    console.error('[ErrorBoundary]', error, info.componentStack);
  }

  render() {
    const { error } = this.state;
    if (error) {
      return (
        <div style={{ background: '#fff', color: '#111', padding: 16, fontFamily: 'monospace', minHeight: '100vh', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
          <h1 style={{ color: '#dc2626', fontSize: 16, marginBottom: 8 }}>Something crashed</h1>
          <p style={{ marginBottom: 8 }}>{error.message}</p>
          <p style={{ fontSize: 12, color: '#666' }}>{error.stack}</p>
        </div>
      );
    }
    return this.props.children;
  }
}
