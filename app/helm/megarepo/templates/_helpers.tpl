{{/*
Expand the name of the chart.
*/}}
{{- define "megarepo.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this
(by the DNS naming spec). If release name contains chart name it will be used
as a full name.
*/}}
{{- define "megarepo.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "megarepo.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "megarepo.labels" -}}
helm.sh/chart: {{ include "megarepo.chart" . }}
{{ include "megarepo.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "megarepo.selectorLabels" -}}
app.kubernetes.io/name: {{ include "megarepo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Return the PostgreSQL hostname.
If the bundled subchart is enabled, use its service name; otherwise use
the external database host provided in values.
*/}}
{{- define "megarepo.databaseHost" -}}
{{- if .Values.postgresql.enabled }}
{{- printf "%s-postgresql" (include "megarepo.fullname" .) }}
{{- else }}
{{- .Values.externalDatabase.host }}
{{- end }}
{{- end }}

{{/*
Return the PostgreSQL port.
*/}}
{{- define "megarepo.databasePort" -}}
{{- if .Values.postgresql.enabled }}
{{- printf "5432" }}
{{- else }}
{{- printf "%d" (.Values.externalDatabase.port | int) }}
{{- end }}
{{- end }}

{{/*
Return the PostgreSQL database name.
*/}}
{{- define "megarepo.databaseName" -}}
{{- if .Values.postgresql.enabled }}
{{- .Values.postgresql.auth.database }}
{{- else }}
{{- .Values.externalDatabase.database }}
{{- end }}
{{- end }}

{{/*
Return the PostgreSQL username.
*/}}
{{- define "megarepo.databaseUser" -}}
{{- if .Values.postgresql.enabled }}
{{- .Values.postgresql.auth.username }}
{{- else }}
{{- .Values.externalDatabase.username }}
{{- end }}
{{- end }}

{{/*
Return the JDBC URL for the datasource.
*/}}
{{- define "megarepo.datasourceUrl" -}}
{{- printf "jdbc:postgresql://%s:%s/%s?stringtype=unspecified" (include "megarepo.databaseHost" .) (include "megarepo.databasePort" .) (include "megarepo.databaseName" .) }}
{{- end }}
