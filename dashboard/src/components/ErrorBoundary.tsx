import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  failed: boolean;
}

/** Last-resort guard so a rendering bug shows a message instead of a blank page. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Only the message is logged: no data from the UI is ever sent anywhere.
    console.error('UI error:', error.message, info.componentStack);
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="page" role="alert">
        <div className="error-state">
          <div className="error-state__body">
            <h1 className="error-state__title">Something went wrong in the dashboard</h1>
            <p className="error-state__text">Reload the page to try again. If it keeps happening, tell the platform team.</p>
          </div>
          <button type="button" className="btn btn--secondary btn--sm" onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      </div>
    );
  }
}
