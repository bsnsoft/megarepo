/* ── API response types matching backend DTOs ────────────────────────── */

export interface Repository {
  name: string;
  format: string;
  type: string;
  url: string;
  online: boolean;
  attributes: Record<string, unknown>;
  componentCount: number;
  assetCount: number;
  totalSize: number;
}

export interface CreateRepositoryRequest {
  name: string;
  format: string;
  type: string;
  online: boolean;
  blobStoreName: string;
  attributes: Record<string, unknown>;
}

export interface Component {
  id: string;
  repository: string;
  format: string;
  group: string;
  name: string;
  version: string;
  assets: Asset[];
}

export interface Asset {
  id: string;
  downloadUrl: string;
  path: string;
  repository: string;
  format: string;
  checksumMd5: string | null;
  checksumSha1: string | null;
  checksumSha256: string | null;
  checksumSha512: string | null;
  contentType: string;
  lastModified: string;
  lastDownloaded: string | null;
  fileSize: number;
}

export interface PageResponse<T> {
  items: T[];
  continuationToken: string | null;
}

export interface ApiUser {
  userId: string;
  firstName: string;
  lastName: string;
  emailAddress: string;
  source: string;
  status: string;
  readOnly: boolean;
  roles: string[];
}

export interface RoleXO {
  id: string;
  name: string;
  description: string;
  source: string;
  readOnly: boolean;
  privileges: string[];
  roles: string[];
}

export interface BlobStore {
  name: string;
  type: string;
  blobCount: number;
  totalSizeInBytes: number;
  availableSpaceInBytes: number | null;
  config: Record<string, unknown>;
}

export interface TaskXO {
  id: string;
  name: string;
  type: string;
  cronExpression: string;
  config: Record<string, unknown>;
  enabled: boolean;
  currentState: string;
  lastRun: string | null;
  lastRunResult: string | null;
  nextRun: string | null;
  message: string | null;
}

export interface AuditLogXO {
  id: number;
  timestamp: string;
  userId: string;
  action: string;
  repository: string;
  path: string;
  sourceUrl: string | null;
  size: number | null;
  ipAddress: string;
  format: string;
  durationMs: number | null;
}

export interface TokenResponse {
  token: string;
}

export interface StatusCheck {
  status: string;
  version: string;
  edition: string;
}

export interface SystemMetrics {
  blobStores: BlobStoreMetric[];
  repositories: RepositoryMetric[];
  totals: TotalMetrics;
}

export interface BlobStoreMetric {
  name: string;
  type: string;
  blobCount: number;
  totalSizeBytes: number;
  availableSpaceBytes: number | null;
}

export interface RepositoryMetric {
  name: string;
  format: string;
  type: string;
  componentCount: number;
  assetCount: number;
}

export interface TotalMetrics {
  repositories: number;
  components: number;
  assets: number;
  totalBlobSizeBytes: number;
}

export interface AnonymousAccessSettings {
  enabled: boolean;
  userId: string;
  realmName: string;
}

export interface SslCertificateXO {
  id: string;
  subjectCommonName: string;
  subjectOrganization: string;
  issuerCommonName: string;
  issuerOrganization: string;
  fingerprint: string;
  serialNumber: string;
  issuedOn: string;
  expiresOn: string;
  pem: string;
}

export interface LdapServerXO {
  name: string;
  sortOrder: number;
  protocol: string;
  hostname: string;
  port: number;
  searchBase: string;
  authScheme: string;
  authUsername: string | null;
  authPassword: string | null;
  connectionTimeout: number;
  retryDelay: number;
  maxRetries: number;
  userBaseDn: string | null;
  userSubtree: boolean;
  userObjectClass: string;
  userIdAttribute: string;
  userNameAttribute: string;
  userEmailAttribute: string;
  ldapGroupsAsRoles: boolean;
  groupType: string;
  groupBaseDn: string | null;
  groupSubtree: boolean;
  groupObjectClass: string | null;
  groupIdAttribute: string | null;
  groupMemberAttribute: string | null;
  groupMemberFormat: string | null;
  userMemberOfAttribute: string | null;
  enabled: boolean;
}

export interface CleanupPolicyXO {
  name: string;
  format: string;
  notes: string;
  criteria: CleanupCriteria;
}

export interface CleanupCriteria {
  lastBlobUpdated?: number;
  lastDownloaded?: number;
  regex?: string;
  releaseType?: 'RELEASES' | 'PRERELEASES';
  retainNVersions?: number;
}

export interface CreateCleanupPolicyRequest {
  name: string;
  format: string | null;
  notes: string | null;
  criteria: CleanupCriteria;
}

export interface CleanupPreviewResponse {
  assetsToDelete: Asset[];
  totalSize: number;
  count: number;
}

export interface RoutingRuleXO {
  name: string;
  description: string;
  mode: string;
  matchers: string[];
  createdAt: string;
}

export interface LicenseStatus {
  licensed: boolean;
  company: string | null;
  email: string | null;
  issuedAt: string | null;
  activeUsers: number;
  requiresPurchase: boolean;
  message: string;
}

export interface NvdFirewallSettings {
  enabled: boolean;
  apiKey: string | null;
  cvssThreshold: number;
}

export interface NvdSyncState {
  status: 'IDLE' | 'SYNCING' | 'ERROR';
  mode: 'FULL' | 'DELTA' | null;
  startedAt: string | null;
  lastSyncAt: string | null;
  lastSuccessAt: string | null;
  totalCves: number;
  syncedCves: number;
  totalResults: number | null;
  errorMessage: string | null;
}

export interface NvdBlock {
  id: number;
  timestamp: string;
  userId: string | null;
  repository: string;
  path: string;
  componentKey: string;
  maxCvssScore: number;
  cveDetails: Array<{
    cveId: string;
    cvssScore: number;
    severity: string | null;
    description: string | null;
  }>;
}

export interface NvdWhitelistEntry {
  id: number;
  entryType: 'COMPONENT' | 'CVE';
  value: string;
  reason: string | null;
  addedAt: string;
  addedBy: string | null;
}

/** Supported repository formats */
export type RepositoryFormat = 'maven2' | 'pypi' | 'npm' | 'nuget' | 'raw' | 'docker';

/** Supported repository types */
export type RepositoryType = 'hosted' | 'proxy' | 'group';
