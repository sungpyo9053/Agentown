import type { Metadata } from "next";
import Link from "next/link";
import { Providers } from "./providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "Agent Village",
  description: "나만의 AI 팀을 만들고 꾸미는 소셜 에이전트 플랫폼",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>
        <Providers>
          <header className="mx-auto flex max-w-6xl items-center justify-between px-6 py-6">
            <Link href="/" className="text-xl font-black tracking-tight">Agent Village</Link>
            <nav className="flex items-center gap-5 text-sm font-semibold">
              <Link href="/dashboard">내 미니홈</Link>
              <Link href="/login" className="rounded-full bg-ink px-4 py-2 text-white">로그인</Link>
            </nav>
          </header>
          {children}
        </Providers>
      </body>
    </html>
  );
}

