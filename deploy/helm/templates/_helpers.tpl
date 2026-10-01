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

{{/* 渲染镜像地址；必须传 ctx=. 才能读到 .Values（image 与 registry 组合）。 */}}
{{- define "pacc.image" -}}
{{- $registry := .ctx.Values.global.imageRegistry -}}
{{- $tag := .ctx.Values.global.imageTag -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" $registry .image $tag -}}
{{- else -}}
{{- printf "%s:%s" .image $tag -}}
{{- end -}}
{{- end -}}

{{/* 内置 MySQL 应用账号密码：未单独配置时回退 root 密码（mysql:8 镜像要求非空才会建 MYSQL_USER）。 */}}
{{- define "pacc.mysqlAppPassword" -}}
{{- if .Values.secrets.mysqlPassword -}}
{{- .Values.secrets.mysqlPassword -}}
{{- else -}}
{{- .Values.secrets.mysqlRootPassword -}}
{{- end -}}
{{- end -}}

{{- define "pacc.mysqlUri" -}}
jdbc:mysql://{{ include "pacc.fullname" . }}-mysql:3306/pacc?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
{{- end -}}