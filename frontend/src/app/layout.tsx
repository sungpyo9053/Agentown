import type { Metadata } from "next";
import { Providers } from "./providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "Agentown",
  description: "Assemble your AI team. 내 회사를 만들고 AI 팀원과 함께 문제를 해결하세요.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        {/* Nike display tier substitute: Bebas Neue (see DESIGN-nike.md "Note on Font Substitutes").
            Inter carries UI/body; Noto Sans KR covers Korean, which Bebas/Inter lack. */}
        <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bebas+Neue&family=Inter:wght@400;500;600;700&family=Noto+Sans+KR:wght@400;500;700&display=swap" />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
