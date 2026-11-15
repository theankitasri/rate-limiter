# Deploy to Minikube

## Prerequisites

- Minikube running: `minikube start --cpus=4 --memory=8192`
- Docker configured to use Minikube's daemon:
  ```bash
  eval $(minikube docker-env)
  ```
- `kubectl` configured for the Minikube cluster

## One-time setup

```bash
# Enable required addons (if not already enabled)
minikube addons enable metrics-server   # for HPA
minikube addons enable ingress          # optional, for Ingress below
```

## Build and deploy

```bash
# 1. Build the Docker image inside Minikube's Docker daemon
eval $(minikube docker-env)
docker build -t rate-limiter:latest .

# 2. Apply all manifests
kubectl apply -f src/main/k8s/

# 3. Verify everything is running
kubectl get pods -n rate-limiter -w
```

## Access the service

### Option A: NodePort (default)

```bash
# Get the minikube IP and NodePort
MINIKUBE_IP=$(minikube ip)
NODE_PORT=$(kubectl get svc rate-limiter -n rate-limiter -o jsonpath='{.spec.ports[0].nodePort}')

# Test
curl -H "X-Api-Key: test-client" http://${MINIKUBE_IP}:${NODE_PORT}/ratelimit/check/test-client
```

### Option B: Port-forward (simpler for local dev)

```bash
kubectl port-forward -n rate-limiter svc/rate-limiter 8080:8080
curl -H "X-Api-Key: test-client" http://localhost:8080/ratelimit/check/test-client
```

## Verify rate limiting

```bash
# Send 5 requests — all should be allowed (default limit: 100)
for i in $(seq 1 5); do
  curl -s -H "X-Api-Key: my-client" http://localhost:8080/ratelimit/check/my-client
done

# Check admin endpoint (basic auth: admin / admin123)
curl -u admin:admin123 http://localhost:8080/actuator/health

# Prometheus metrics
curl -u admin:admin123 http://localhost:8080/actuator/prometheus | grep ratelimit
```

## Clean up

```bash
kubectl delete -f src/main/k8s/
```

## Optional: Ingress (for nicer local access)

```yaml
# src/main/k8s/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: rate-limiter
  namespace: rate-limiter
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  rules:
    - host: rate-limiter.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: rate-limiter
                port:
                  number: 8080
```

```bash
# Add to /etc/hosts (minikube IP)
echo "$(minikube ip) rate-limiter.local" | sudo tee -a /etc/hosts
# Apply
kubectl apply -f src/main/k8s/ingress.yaml
```
