import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { initRUM } from '@ep/shared';
import './index.css';

// D-235: Real User Monitoring — Core Web Vitals → BFF /api/v1/rum → Micrometer.
// 10% sampling per visit; beacons are fire-and-forget and carry no PII.
initRUM({ endpoint: '/api/v1/rum', sampleRate: 0.1 });

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element #root not found');
}

createRoot(rootEl).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
