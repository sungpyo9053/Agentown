import { OnboardingCompanyForm } from "@/components/OnboardingCompanyForm";

export default async function OnboardingCompanyPage({ searchParams }: { searchParams: Promise<{ next?: string }> }) {
  const { next } = await searchParams;
  return <OnboardingCompanyForm nextPath={next} />;
}
