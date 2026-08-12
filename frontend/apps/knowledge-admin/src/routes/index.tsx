import { Routes, Route, Navigate } from 'react-router-dom';
import KnowledgeLayout from '../layouts/KnowledgeLayout';
import KnowledgeBasePage from '../pages/KnowledgeBasePage';
import DocumentUploadPage from '../pages/DocumentUploadPage';
import RetrievalTestPage from '../pages/RetrievalTestPage';
import AgentManagePage from '../pages/AgentManagePage';
import CommunityPage from '../pages/CommunityPage';
import TenantManagePage from '../pages/TenantManagePage';
import LearningPathPage from '../pages/LearningPathPage';

export function AppRoutes() {
  return (
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
  );
}

export default AppRoutes;
