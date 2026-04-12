import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { ToastProvider } from './components/Toast';
import ErrorBoundary from './components/ErrorBoundary';
import ProtectedRoute from './auth/ProtectedRoute';
import AppLayout from './layout/AppLayout';
import LoginPage from './auth/LoginPage';
import DashboardPage from './pages/DashboardPage';
import RepositoryListPage from './pages/repositories/RepositoryListPage';
import RepositoryCreatePage from './pages/repositories/RepositoryCreatePage';
import RepositoryDetailPage from './pages/repositories/RepositoryDetailPage';
import RepositoryEditPage from './pages/repositories/RepositoryEditPage';
import BrowsePage from './pages/browse/BrowsePage';
import BrowseRepositoryPage from './pages/browse/BrowseRepositoryPage';
import ComponentDetailPage from './pages/browse/ComponentDetailPage';
import SearchPage from './pages/search/SearchPage';
import UploadPage from './pages/upload/UploadPage';
import BlobStoresPage from './pages/blobstores/BlobStoresPage';
import UsersPage from './pages/security/UsersPage';
import RolesPage from './pages/security/RolesPage';
import LdapPage from './pages/security/LdapPage';
import SslCertificatesPage from './pages/security/SslCertificatesPage';
import AnonymousAccessPage from './pages/security/AnonymousAccessPage';
import StatusPage from './pages/system/StatusPage';
import TasksPage from './pages/system/TasksPage';
import AuditLogPage from './pages/system/AuditLogPage';
import AccountPage from './pages/account/AccountPage';
import CachePage from './pages/cache/CachePage';
import RoutingRulesPage from './pages/routing/RoutingRulesPage';
import LicensePage from './pages/system/LicensePage';

export default function App() {
  return (
    <ErrorBoundary>
    <BrowserRouter>
      <AuthProvider>
        <ToastProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />

            <Route
              element={
                <ProtectedRoute>
                  <AppLayout />
                </ProtectedRoute>
              }
            >
              <Route path="/" element={<DashboardPage />} />
              <Route path="/browse" element={<BrowsePage />} />
              <Route path="/browse/:repositoryName" element={<BrowseRepositoryPage />} />
              <Route path="/browse/:repositoryName/components/:componentId" element={<ComponentDetailPage />} />
              <Route path="/search" element={<SearchPage />} />
              <Route path="/upload" element={<UploadPage />} />

              {/* Administration - Repository */}
              <Route path="/admin/repositories" element={<RepositoryListPage />} />
              <Route path="/admin/repositories/create" element={<RepositoryCreatePage />} />
              <Route path="/admin/repositories/:name" element={<RepositoryDetailPage />} />
              <Route path="/admin/repositories/:name/edit" element={<RepositoryEditPage />} />
              <Route path="/admin/blobstores" element={<BlobStoresPage />} />
              <Route path="/admin/cleanup" element={<CachePage />} />
              <Route path="/admin/routing-rules" element={<RoutingRulesPage />} />

              {/* Administration - Security */}
              <Route path="/admin/users" element={<UsersPage />} />
              <Route path="/admin/roles" element={<RolesPage />} />
              <Route path="/admin/ldap" element={<LdapPage />} />
              <Route path="/admin/ssl" element={<SslCertificatesPage />} />
              <Route path="/admin/anonymous" element={<AnonymousAccessPage />} />

              {/* Administration - System */}
              <Route path="/admin/status" element={<StatusPage />} />
              <Route path="/admin/tasks" element={<TasksPage />} />
              <Route path="/admin/audit" element={<AuditLogPage />} />
              <Route path="/admin/license" element={<LicensePage />} />

              {/* Account */}
              <Route path="/account" element={<AccountPage />} />
            </Route>

            {/* Catch-all */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </BrowserRouter>
    </ErrorBoundary>
  );
}
