{{- define "miniurl.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .name }}
app.kubernetes.io/part-of: miniurl
app.kubernetes.io/managed-by: {{ .managedBy | default "helm" }}
{{- end }}

{{- define "miniurl.image" -}}
{{- $svc := .svc -}}
{{- $root := .root -}}
{{- $imageTag := "" -}}
{{- if $root.Values.globalConfig -}}
{{- $imageTag = index $root.Values.globalConfig "IMAGE_TAG" -}}
{{- end -}}
{{- if $imageTag -}}
{{- /* Legacy mode: global IMAGE_TAG applied to per-service repositories (GHCR) */ -}}
{{ $svc.image.repository }}:{{ $imageTag }}
{{- else if $svc.image.tag -}}
{{- /* New mode: per-service image.tag, use global IMAGE_REPOSITORY if set (Docker Hub single repo) */ -}}
{{- $repo := $svc.image.repository -}}
{{- if and $root.Values.globalConfig (index $root.Values.globalConfig "IMAGE_REPOSITORY") -}}
{{- $repo = index $root.Values.globalConfig "IMAGE_REPOSITORY" -}}
{{- end -}}
{{ $repo }}:{{ $svc.image.tag }}
{{- else -}}
{{ $svc.image.repository }}:latest
{{- end -}}
{{- end }}

{{- define "miniurl.serviceName" -}}
{{- if .canary -}}
{{ .name }}-canary
{{- else -}}
{{ .name }}
{{- end -}}
{{- end }}
