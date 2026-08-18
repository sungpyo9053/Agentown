"use client";

import { FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { AppShell, Panel } from "@/components/AppShell";

type Profile = { displayName: string; bio?: string; avatarUrl?: string };
type Me = { displayName: string; handle: string; role: "USER" | "ADMIN" };

export default function PreferencesPage() {
  const router = useRouter();
  const client = useQueryClient();
  const me = useQuery({ queryKey: ["me"], queryFn: () => api<Me>("/auth/me"), retry: false });
  const profile = useQuery({ queryKey: ["profile-me"], queryFn: () => api<Profile>("/users/me") });
  const saveProfile = useMutation({ mutationFn: (body: unknown) => api<Profile>("/users/me", { method: "PATCH", body: JSON.stringify(body) }), onSuccess: (data) => client.setQueryData(["profile-me"], data) });
  const changePassword = useMutation({ mutationFn: (body: unknown) => api<void>("/users/me/password", { method: "PATCH", body: JSON.stringify(body) }) });
  const withdraw = useMutation({ mutationFn: (body: unknown) => api<void>("/users/me", { method: "DELETE", body: JSON.stringify(body) }), onSuccess: () => router.push("/") });

  function submitPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    changePassword.mutate({ currentPassword: form.get("currentPassword"), newPassword: form.get("newPassword") });
  }
  function submitWithdraw(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!window.confirm("계정을 탈퇴하면 다시 로그인할 수 없습니다. 계속할까요?")) return;
    const form = new FormData(event.currentTarget);
    withdraw.mutate({ currentPassword: form.get("currentPassword") });
  }

  const input = "mt-2 w-full border border-hairline px-4 py-3 outline-none focus:border-ink";

  return <AppShell kicker="SETTING" title="환경 설정">
    <div className="max-w-2xl space-y-2">
      <Panel title="내 프로필" action={<span className="text-sm font-medium text-mute">@{me.data?.handle}</span>}>
        <label className="block text-sm font-medium text-ink">표시 이름
          <input value={profile.data?.displayName ?? ""} onChange={(e) => client.setQueryData<Profile>(["profile-me"], (old) => ({ ...old!, displayName: e.target.value }))} className={input} />
        </label>
        <label className="mt-4 block text-sm font-medium text-ink">소개
          <textarea value={profile.data?.bio ?? ""} onChange={(e) => client.setQueryData<Profile>(["profile-me"], (old) => ({ ...old!, bio: e.target.value }))} rows={3} className={input} />
        </label>
        <button type="button" onClick={() => saveProfile.mutate({ displayName: profile.data?.displayName, bio: profile.data?.bio, avatarUrl: profile.data?.avatarUrl ?? "" })} className="mt-5 rounded-pill bg-ink px-8 py-4 text-sm font-medium text-white transition active:scale-95 active:opacity-50">프로필 저장</button>
        {saveProfile.error && <p className="mt-3 text-sm text-sale">{saveProfile.error.message}</p>}
      </Panel>

      <Panel title="비밀번호 변경">
        <form onSubmit={submitPassword} className="space-y-3">
          <input type="password" name="currentPassword" minLength={8} required placeholder="현재 비밀번호" className="w-full border border-hairline px-4 py-3 outline-none focus:border-ink" />
          <input type="password" name="newPassword" minLength={8} required placeholder="새 비밀번호" className="w-full border border-hairline px-4 py-3 outline-none focus:border-ink" />
          <button className="rounded-pill bg-ink px-8 py-4 text-sm font-medium text-white transition active:scale-95 active:opacity-50">변경</button>
          {changePassword.isSuccess && <p className="text-sm text-leaf">비밀번호를 변경했습니다.</p>}
          {changePassword.error && <p className="text-sm text-sale">{changePassword.error.message}</p>}
        </form>
      </Panel>

      <Panel title="계정 탈퇴">
        <p className="text-sm text-sale">로그인을 차단하고 이메일·휴대폰 인증정보를 제거합니다.</p>
        <form onSubmit={submitWithdraw} className="mt-4 space-y-3">
          <input type="password" name="currentPassword" minLength={8} required placeholder="현재 비밀번호 확인" className="w-full border border-hairline px-4 py-3 outline-none focus:border-ink" />
          <button className="rounded-pill border border-hairline px-8 py-4 text-sm font-medium text-sale transition active:scale-95">탈퇴</button>
          {withdraw.error && <p className="text-sm text-sale">{withdraw.error.message}</p>}
        </form>
      </Panel>
    </div>
  </AppShell>;
}
