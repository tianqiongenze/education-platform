import { Routes, Route, Navigate } from 'react-router-dom';
import { Suspense, lazy } from 'react';
import KnowledgeLayout from '../layouts/KnowledgeLayout';
// D-235: route-level code splitting — the knowledge-bases landing stays
// eager; the remaining six pages are on-demand chunks.
import KnowledgeBasePage from '../pages/KnowledgeBasePage';

const DocumentUploadPage = lazy(() => import('../pages/DocumentUploadPage'));
const RetrievalTestPage = lazy(() => import('../pages/RetrievalTestPage'));
const AgentManagePage = lazy(() => import('../pages/AgentManagePage'));
const CommunityPage = lazy(() => import('../pages/CommunityPage'));
const TenantManagePage = lazy(() => import('../pages/TenantManagePage'));
const LearningPathPage = lazy(() => import('../pages/LearningPathPage'));

/** Minimal instant-paint fallback while a route chunk downloads. */
function PageFallback() {
  return (
    <div style={{ minHeight: '40vh', display: 'grid', placeItems: 'center' }} aria-busy="true">
      <span>加载中…</span>
    </div>
  );
}

export function AppRoutes() {
  return (
    <Suspense fallback={<PageFallback />}>
      <Routes>
        <Route path="/" element={<KnowledgeLayout />}>
          <Route index element={<Navigate to="/knowledge-bases" replace />} />
          <Route path="knowledge-bases" element={<KnowledgeBasePage />} />
          <Route path="documents" element={<DocumentUploadPage />} />
          <Route path="retrieval" element={<RetrievalTestPage />} />
          <Route path="agents" element={<AgentManagePage />} />
          <Route path="community" element={<CommunityPage />} />
          <Route path="tenants" element={<TenantManagePage />} />
          <Route path="learning-paths" element={<LearningPathPage />} />
          <Route path="*" element={<Navigate to="/knowledge-bases" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}

export default AppRoutes;
