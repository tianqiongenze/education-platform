import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { initRUM } from '@ep/shared';
import './styles/global.css';

// D-235: Real User Monitoring — Core Web Vitals → BFF /api/v1/rum → Micrometer.
initRUM({ endpoint: '/api/v1/rum', sampleRate: 0.1 });

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element #root not found');
}

createRoot(rootEl).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
