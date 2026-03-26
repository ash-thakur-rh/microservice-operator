# HPA Demo Checklist

## 1. Install metrics-server (if not present)

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml && kubectl patch deployment metrics-server -n kube-system --type=json -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

## 2. Verify metrics are flowing (wait ~60s after install)

```bash
kubectl top pods -n production
```

Expected output — real CPU/memory numbers, not `<unknown>`:
```
NAME                              CPU(cores)   MEMORY(bytes)
petclinic-prod-7cdf748cd8-hhgjz   1m           297Mi
petclinic-prod-7cdf748cd8-q59d6   1m           293Mi
petclinic-prod-db-0               3m           59Mi
```

## 3. Run the load script

```bash
./scripts/hpa-demo.sh petclinic.apps.apps-crc.testing 50 180
```

- 50 concurrent workers
- 180 seconds duration
- Needs to push CPU above 20% of 500m request = 100m cores

## 4. Watch HPA scale out (separate terminal)

```bash
kubectl get hpa -n production -w
```