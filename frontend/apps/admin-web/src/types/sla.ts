/**
 * D-244: SLA status types matching the BFF SlaController contract
 * (GET /api/v1/sla — see services/portal-bff SlaController).
 */

export type SlaVerdict = 'ok' | 'breach' | 'insufficient-data';

/** One latency class (read / write / payment) verdict. */
export interface SlaClass {
  /** Present only when verdict is ok/breach. */
  count?: number;
  /** Present only when verdict is ok/breach. */
  p95Ms?: number;
  targetP95Ms?: number;
  errorRate?: number;
  /** "low" when samples < 100. */
  sample?: 'low' | 'sufficient';
  verdict: SlaVerdict;
}

export interface SlaReport {
  status: 'live';
  classes: {
    read: SlaClass;
    write: SlaClass;
    payment: SlaClass;
  };
  overall: 'ok' | 'degraded';
}
