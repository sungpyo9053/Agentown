import Link from "next/link";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return <><header className="mx-auto flex max-w-6xl items-center justify-between px-6 py-6"><Link href="/" className="text-lg font-semibold tracking-tight">Agentown</Link><Link href="/" className="text-sm font-medium text-zinc-600 hover:text-ink">서비스 소개</Link></header><main className="mx-auto flex min-h-[calc(100vh-100px)] max-w-md items-center px-6 py-12">{children}</main></>;
}
