type Listener = () => void;

const listeners = new Set<Listener>();

export const authBridge = {
  onUnauthenticated(cb: Listener): () => void {
    listeners.add(cb);
    return () => listeners.delete(cb);
  },
  emitUnauthenticated(): void {
    for (const cb of listeners) {
      cb();
    }
  },
};
