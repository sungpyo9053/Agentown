export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return <main className="mx-auto flex min-h-[calc(100vh-100px)] max-w-md items-center px-6 py-12">{children}</main>;
}

