import { MarketingHeader, MarketingFooter } from "@/components/MarketingShell";
import { LandingCarousel } from "@/components/LandingCarousel";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col bg-white">
      <MarketingHeader />
      <LandingCarousel />
      <MarketingFooter />
    </main>
  );
}
