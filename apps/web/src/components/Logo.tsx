import { clsx } from "clsx";

import { CheckShieldIcon } from "./icons";

interface LogoProps {
  className?: string;
  showWordmark?: boolean;
}

export function Logo({ className, showWordmark = true }: LogoProps) {
  return (
    <div className={clsx("flex items-center gap-2", className)}>
      <span className="flex h-8 w-8 items-center justify-center rounded-md bg-gradient-accent text-white">
        <CheckShieldIcon />
      </span>
      {showWordmark && (
        <span className="text-primary font-semibold">QualityOps</span>
      )}
    </div>
  );
}
