"use client";

import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import { api } from "@/lib/api";

type Profile = { displayName: string; bio?: string; avatarUrl?: string };
type Me = { displayName: string; handle: string; role: "USER" | "ADMIN" };

export default function MyPage() {
  const router = useRouter();
  const client = useQueryClient();
  const me = useQuery({ queryKey: ["me"], queryFn: () => api<Me>("/auth/me"), retry: false });
  const profile = useQuery({ queryKey: ["profile-me"], queryFn: () => api<Profile>("/users/me") });
  const saveProfile = useMutation({ mutationFn: (body: unknown) => api<Profile>("/users/me", { method: "PATCH", body: JSON.stringify(body) }), onSuccess: (data) => client.setQueryData(["profile-me"], data) });
  const changePassword = useMutation({ mutationFn: (body: unknown) => api<void>("/users/me/password", { method: "PATCH", body: JSON.stringify(body) }) });
  const withdraw = useMutation({ mutationFn: (body: unknown) => api<void>("/users/me", { method: "DELETE", body: JSON.stringify(body) }), onSuccess: () => router.push("/") });
  const logout = useMutation({ mutationFn: () => api<void>("/auth/logout", { method: "POST" }), onSuccess: () => { client.clear(); router.push("/"); router.refresh(); } });

  return <AppShell kicker="MY PAGE" title="마이페이지">
    <div className="grid gap-6 md:grid-cols-[1fr_320px]">
      <div className="space-y-6">
        <section className="rounded-3xl bg-white p-6 shadow-card">
          <h2 className="font-black">내 프로필</h2>
          <p className="mt-1 text-sm text-stone-500">@{me.data?.handle}</p>
          <label className="mt-4 block text-sm font-bold">표시 이름<input value={profile.data?.displayName ?? ""} onChange={e => client.setQueryData<Profile>(["profile-me"], old => ({ ...old!, displayName: e.target.value }))} className="mt-2 w-full rounded-xl border p-3" /></label>
          <label className="mt-4 block text-sm font-bold">소개<textarea value={profile.data?.bio ?? ""} onChange={e => client.setQueryData<Profile>(["profile-me"], old => ({ ...old!, bio: e.target.value }))} rows={3} className="mt-2 w-full rounded-xl border p-3" /></label>
          <button type="button" onClick={() => saveProfile.mutate({ displayName: profile.data?.displayName, bio: profile.data?.bio, avatarUrl: profile.data?.avatarUrl ?? "" })} className="mt-4 w-full rounded-xl border p-3 font-bold">프로필 저장</button>
          {saveProfile.error && <p className="mt-2 text-sm text-red-600">{saveProfile.error.message}</p>}
        </section>

        <section className="rounded-3xl bg-white p-6 shadow-card">
          <h2 className="font-black">비밀번호 변경</h2>
          <form onSubmit={e => { e.preventDefault(); const f = new FormData(e.currentTarget); changePassword.mutate({ currentPassword: f.get("currentPassword"), newPassword: f.get("newPassword") }); }} className="mt-4 space-y-3">
            <input type="password" name="currentPassword" minLength={8} required placeholder="현재 비밀번호" className="w-full rounded-xl border p-3" />
            <input type="password" name="newPassword" minLength={8} required placeholder="새 비밀번호" className="w-full rounded-xl border p-3" />
            <button className="rounded-xl bg-ink px-5 py-3 font-bold text-white">변경</button>
            {changePassword.isSuccess && <p className="text-sm text-leaf">비밀번호를 변경했습니다.</p>}
            {changePassword.error && <p className="text-sm text-red-600">{changePassword.error.message}</p>}
          </form>
        </section>

        <section className="rounded-3xl border border-red-100 bg-red-50 p-6">
          <h2 className="font-black text-red-700">계정 탈퇴</h2>
          <p className="mt-1 text-sm text-red-600">로그인을 차단하고 이메일·휴대폰 인증정보를 제거합니다.</p>
          <form onSubmit={e => { e.preventDefault(); if (!window.confirm("계정을 탈퇴하면 다시 로그인할 수 없습니다. 계속할까요?")) return; const f = new FormData(e.currentTarget); withdraw.mutate({ currentPassword: f.get("currentPassword") }); }} className="mt-4 space-y-3">
            <input type="password" name="currentPassword" minLength={8} required placeholder="현재 비밀번호 확인" className="w-full rounded-xl border p-3" />
            <button className="rounded-xl border border-red-300 bg-white px-5 py-3 font-bold text-red-700">탈퇴</button>
            {withdraw.error && <p className="text-sm text-red-600">{withdraw.error.message}</p>}
          </form>
        </section>
      </div>

      <aside className="space-y-4">
        <div className="rounded-3xl bg-ink p-6 text-white">
          <p className="text-xs font-bold text-coral">ACCOUNT</p>
          <p className="mt-2 text-lg font-black">{me.data?.displayName}</p>
          <p className="text-sm text-stone-300">@{me.data?.handle}</p>
          <button onClick={() => logout.mutate()} disabled={logout.isPending} className="mt-4 w-full rounded-xl border border-white/20 py-2 text-sm font-bold hover:bg-white/10">로그아웃</button>
        </div>
        <Link href="/me/billing" className="block rounded-3xl bg-white p-6 shadow-card"><h2 className="font-bold">🏠 구독 · 오피스 월세</h2><p className="mt-2 text-sm text-stone-500">무료 체험과 요금제를 확인합니다.</p></Link>
        <Link href="/settings/credentials" className="block rounded-3xl bg-white p-6 shadow-card"><h2 className="font-bold">🔐 AI 연결 관리</h2><p className="mt-2 text-sm text-stone-500">모델 API 키를 등록·확인합니다.</p></Link>
        <Link href="/home/edit" className="block rounded-3xl bg-white p-6 shadow-card"><h2 className="font-bold">🏢 회사 설정으로</h2><p className="mt-2 text-sm text-stone-500">회사 이름·소개·오피스 테마를 수정합니다.</p></Link>
      </aside>
    </div>
  </AppShell>;
}
