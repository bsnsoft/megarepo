import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, ADMIN_ROLE } from './auth/AuthContext';
import { ToastProvider } from './components/Toast';
import ErrorBoundary from './components/ErrorBoundary';
import ProtectedRoute from './auth/ProtectedRoute';
import RequireRole from './auth/RequireRole';
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
import NvdFirewallPage from './pages/security/NvdFirewallPage';
import RepositoryFirewallPage from './pages/security/RepositoryFirewallPage';
import QuarantineQueuePage from './pages/security/firewall/QuarantineQueuePage';
import PolicyListPage from './pages/security/firewall/PolicyListPage';
import PolicyEditorPage from './pages/security/firewall/PolicyEditorPage';
import ExemptionsPage from './pages/security/firewall/ExemptionsPage';
import StatusPage from './pages/system/StatusPage';
import TasksPage from './pages/system/TasksPage';
import AuditLogPage from './pages/system/AuditLogPage';
import AccountPage from './pages/account/AccountPage';
import CachePage from './pages/cache/CachePage';
import RoutingRulesPage from './pages/routing/RoutingRulesPage';
import LicensePage from './pages/system/LicensePage';
import HttpProxyPage from './pages/system/HttpProxyPage';

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

              {/* Administration - System, open to any logged-in account */}
              <Route path="/admin/status" element={<StatusPage />} />
              <Route path="/admin/tasks" element={<TasksPage />} />
              <Route path="/admin/audit" element={<AuditLogPage />} />

              {/*
                Administrator-only screens. Grouping them under one guard keeps
                a pasted URL behaving like a click in the navigation, and keeps
                a non-administrator out of pages that would render as nothing
                but permission errors. The list of routes below is the one in
                auth/adminAreas.ts, which the sidebar filters by — the two have
                to stay in step.
              */}
              <Route element={<RequireRole role={ADMIN_ROLE} />}>
                {/* Security */}
                <Route path="/admin/users" element={<UsersPage />} />
                <Route path="/admin/roles" element={<RolesPage />} />
                <Route path="/admin/ldap" element={<LdapPage />} />
                <Route path="/admin/ssl" element={<SslCertificatesPage />} />
                <Route path="/admin/anonymous" element={<AnonymousAccessPage />} />
                <Route path="/admin/nvd-firewall" element={<NvdFirewallPage />} />
                <Route path="/admin/firewall" element={<RepositoryFirewallPage />} />
                <Route path="/admin/firewall/quarantine" element={<QuarantineQueuePage />} />
                <Route path="/admin/firewall/policies" element={<PolicyListPage />} />
                <Route path="/admin/firewall/policies/:id" element={<PolicyEditorPage />} />
                <Route path="/admin/firewall/exemptions" element={<ExemptionsPage />} />

                {/* System */}
                <Route path="/admin/http-proxy" element={<HttpProxyPage />} />
                <Route path="/admin/license" element={<LicensePage />} />
              </Route>

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
