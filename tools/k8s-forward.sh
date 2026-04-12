#!/bin/bash
# Persistent port-forward for MegaRepo K8s → localhost:9090
# Install as launchd agent for auto-start

while true; do
  echo "[$(date)] Starting port-forward..."
  /usr/local/bin/kubectl port-forward -n megarepo svc/megarepo 9090:8080 2>&1
  echo "[$(date)] Port-forward died, restarting in 3s..."
  sleep 3
done
