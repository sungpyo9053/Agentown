import { MarketingHeader, MarketingFooter } from "@/components/MarketingShell";
import { LandingExperience } from "@/components/LandingExperience";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col bg-white">
      <MarketingHeader />
      <LandingExperience />
      <MarketingFooter />
    </main>
  );
}
