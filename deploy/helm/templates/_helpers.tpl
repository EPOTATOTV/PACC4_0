{{- define "pacc.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "pacc.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "pacc.labels" -}}
app.kubernetes.io/part-of: pacc
app.kubernetes.io/version: {{ .Values.global.imageTag | quote }}
{{- end -}}

{{- define "pacc.image" -}}
{{- $registry := .Values.global.imageRegistry -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" $registry .image .Values.global.imageTag -}}
{{- else -}}
{{- printf "%s:%s" .image .Values.global.imageTag -}}
{{- end -}}
{{- end -}}

{{- define "pacc.mysqlUri" -}}
jdbc:mysql://{{ include "pacc.fullname" . }}-mysql:3306/pacc?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
{{- end -}}