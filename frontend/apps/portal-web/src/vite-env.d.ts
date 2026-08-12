/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_APP_VERSION: string;
  readonly VITE_CELL_ID: string;
  /** External app URLs for business-unit navigation. */
  readonly VITE_ADMIN_WEB_URL: string;
  readonly VITE_STUDENT_H5_URL: string;
  readonly VITE_KNOWLEDGE_ADMIN_URL: string;
  readonly VITE_KNOWLEDGE_H5_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
