import {
  Activity,
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  Bell,
  BookOpen,
  Boxes,
  Check,
  ChevronDown,
  CircleHelp,
  Clock3,
  Command,
  Database,
  Download,
  ExternalLink,
  Filter,
  Gauge,
  GitBranch,
  KeyRound,
  Layers3,
  LockKeyhole,
  Menu,
  MoreHorizontal,
  Network,
  Pause,
  Play,
  Radar,
  RefreshCw,
  Search,
  Server,
  Shield,
  ShieldAlert,
  Sparkles,
  TerminalSquare,
  TrendingUp,
  UserRound,
  Users,
  Wifi,
  X,
  Zap,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState, useRef, type ComponentType } from "react";
import { Area, AreaChart, Bar, BarChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { toast } from "sonner";

type Icon = ComponentType<{ size?: number; strokeWidth?: number; className?: string }>;
type NavId = "command" | "traffic" | "risk" | "streams" | "redis";

type Stats = {
  totalRequests: number;
  blockedRequests: number;
  flaggedLogins: number;
  hotlistSize: number;
};

type HotlistEntry = {
  username: string;
  score: number;
  label: string;
  status: string;
};

type Detection = {
  time: string;
  actor: string;
  signal: string;
  source: string;
  score: number;
  severity: "critical" | "elevated" | "monitored";
};

const API_BASE = (import.meta.env.VITE_CACHEGUARD_API_URL ?? "").replace(/\/$/, "");
const navItems: { id: NavId; label: string; icon: Icon; target: string }[] = [
  { id: "command", label: "Command center", icon: Radar, target: "command-center" },
  { id: "traffic", label: "Traffic shield", icon: Gauge, target: "traffic-shield" },
  { id: "risk", label: "Risk hotlist", icon: ShieldAlert, target: "risk-hotlist" },
  { id: "streams", label: "Login streams", icon: GitBranch, target: "login-streams" },
  { id: "redis", label: "Redis topology", icon: Database, target: "redis-topology" },
];

const trafficData = [
  { time: "09:00", allowed: 218, blocked: 12, pressure: 18 },
  { time: "09:10", allowed: 268, blocked: 17, pressure: 26 },
  { time: "09:20", allowed: 246, blocked: 22, pressure: 24 },
  { time: "09:30", allowed: 338, blocked: 38, pressure: 44 },
  { time: "09:40", allowed: 372, blocked: 64, pressure: 61 },
  { time: "09:50", allowed: 408, blocked: 73, pressure: 72 },
  { time: "10:00", allowed: 394, blocked: 56, pressure: 59 },
  { time: "10:10", allowed: 432, blocked: 41, pressure: 48 },
  { time: "10:20", allowed: 447, blocked: 35, pressure: 42 },
  { time: "10:30", allowed: 465, blocked: 29, pressure: 34 },
  { time: "10:40", allowed: 482, blocked: 26, pressure: 31 },
  { time: "10:50", allowed: 496, blocked: 22, pressure: 27 },
];

const seedHotlist: HotlistEntry[] = [
  { username: "amanda.chen", score: 92, label: "Credential stuffing", status: "blocked" },
  { username: "mikhail.ivanov", score: 78, label: "Device flood", status: "captcha" },
  { username: "tanya.kim", score: 64, label: "Known credential", status: "captcha" },
  { username: "service-billing", score: 42, label: "IP velocity", status: "monitoring" },
  { username: "ravi.singh", score: 28, label: "Device drift", status: "monitoring" },
];

const seedDetections: Detection[] = [
  { time: "10:48:21", actor: "amanda.chen", signal: "Credential stuffing", source: "185.220.101.24", score: 92, severity: "critical" },
  { time: "10:45:07", actor: "mikhail.ivanov", signal: "Device flood", source: "103.7.19.88", score: 78, severity: "elevated" },
  { time: "10:41:54", actor: "tanya.kim", signal: "Known credential", source: "45.148.10.16", score: 64, severity: "elevated" },
  { time: "10:37:12", actor: "service-billing", signal: "IP velocity", source: "172.16.40.8", score: 42, severity: "monitored" },
];

const normalizeStats = (payload: any): Stats => ({
  totalRequests: Number(payload?.totalRequests ?? payload?.requestsTotal ?? payload?.total ?? 0),
  blockedRequests: Number(payload?.blockedCount ?? payload?.blockedRequests ?? payload?.requestsBlocked ?? payload?.blocked ?? 0),
  flaggedLogins: Number(payload?.flaggedCount ?? payload?.flaggedLogins ?? payload?.loginsFlagged ?? payload?.flagged ?? 0),
  hotlistSize: Number(payload?.hotlistSize ?? payload?.riskHotlistSize ?? payload?.hotlist ?? 0),
});

const formatCompact = (value: number) => {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(value >= 100_000 ? 0 : 1)}K`;
  return value.toLocaleString();
};

function useCacheGuardData() {
  const [stats, setStats] = useState<Stats>({ totalRequests: 2_834_210, blockedRequests: 18_402, flaggedLogins: 1_284, hotlistSize: 37 });
  const [hotlist, setHotlist] = useState(seedHotlist);
  const [lastSynced, setLastSynced] = useState("just now");
  const [connected, setConnected] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const refresh = useCallback(async (showToast = false) => {
    setRefreshing(true);
    try {
      const [statsResponse, hotlistResponse] = await Promise.all([
        fetch(`${API_BASE}/api/stats`, { signal: AbortSignal.timeout(2400) }),
        fetch(`${API_BASE}/api/hotlist?top=5`, { signal: AbortSignal.timeout(2400) }),
      ]);
      if (!statsResponse.ok || !hotlistResponse.ok) throw new Error("CacheGuard API unavailable");
      const nextStats = normalizeStats(await statsResponse.json());
      const nextHotlist = await hotlistResponse.json();
      setStats(nextStats);
      if (Array.isArray(nextHotlist) && nextHotlist.length) {
        setHotlist(nextHotlist.map((entry: any, index: number) => ({
          username: entry.username ?? entry.user ?? `risk-actor-${index + 1}`,
          score: Number(entry.score ?? entry.riskScore ?? 0),
          label: entry.label ?? "Anomaly signal",
          status: Number(entry.score ?? entry.riskScore ?? 0) >= 85 ? "blocked" : Number(entry.score ?? entry.riskScore ?? 0) >= 55 ? "captcha" : "monitoring",
        })));
      }
      setConnected(true);
      setLastSynced("just now");
      if (showToast) toast.success("CacheGuard metrics synced", { description: "Live counters and hotlist are up to date." });
    } catch {
      setConnected(false);
      setLastSynced("fallback mode");
      if (showToast) toast.info("Showing demo telemetry", { description: "Start the Spring Boot service to stream live Redis metrics." });
    } finally {
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    const interval = window.setInterval(() => void refresh(), 30000);
    return () => window.clearInterval(interval);
  }, [refresh]);

  return { stats, hotlist, lastSynced, connected, refreshing, refresh };
}

function LogoMark() {
  return (
    <div className="relative grid h-9 w-9 place-items-center rounded-xl border border-cyan-200/30 bg-cyan-300/10 text-cyan-200">
      <Shield size={18} strokeWidth={2.4} />
      <span className="absolute -right-1 -top-1 h-2 w-2 rounded-full bg-lime-300 shadow-[0_0_12px_rgba(200,255,82,.9)]" />
    </div>
  );
}

function Sidebar({ active, onNavigate }: { active: NavId; onNavigate: (id: NavId, target: string) => void }) {
  return (
    <aside className="sidebar-shell fixed inset-y-0 left-0 z-20 flex w-[246px] flex-col border-r border-white/[.07] bg-[#0a0f13]/90 px-4 py-5 backdrop-blur-2xl">
      <div className="sidebar-brand flex items-center gap-3 px-2">
        <LogoMark />
        <div className="sidebar-copy min-w-0">
          <p className="display-font text-[15px] font-bold tracking-[-.04em] text-[#eef8ee]">CacheGuard</p>
          <p className="mt-0.5 text-[9px] font-bold uppercase tracking-[.18em] text-[#71827d]">security console</p>
        </div>
      </div>

      <div className="mt-10 px-2"><p className="eyebrow">Observe</p></div>
      <nav className="mt-3 space-y-1">
        {navItems.map((item) => {
          const IconComponent = item.icon;
          return (
            <button key={item.id} onClick={() => onNavigate(item.id, item.target)} className={`nav-button ${active === item.id ? "active" : ""}`} aria-current={active === item.id ? "page" : undefined}>
              <IconComponent size={16} strokeWidth={active === item.id ? 2.2 : 1.8} />
              <span className="sidebar-copy">{item.label}</span>
              {item.id === "risk" && <span className="sidebar-copy ml-auto rounded-full bg-coral-400/10 px-1.5 py-0.5 font-mono text-[9px] text-[#ff9c87]">37</span>}
            </button>
          );
        })}
      </nav>

      <div className="mt-8 px-2"><p className="eyebrow">Control plane</p></div>
      <div className="mt-3 space-y-1">
        <button className="nav-button" onClick={() => toast.info("API keys are managed in Spring Boot configuration.")}><KeyRound size={16} /><span className="sidebar-copy">API credentials</span></button>
        <button className="nav-button" onClick={() => toast.info("Runbook export is ready to connect to your incident workflow.")}><BookOpen size={16} /><span className="sidebar-copy">Runbooks</span></button>
      </div>

      <div className="mt-auto space-y-4">
        <div className="sidebar-copy glass-soft rounded-2xl p-3.5">
          <div className="flex items-center justify-between"><span className="eyebrow">Protection</span><span className="status-pill lime !px-2 !py-1"><span className="lime-dot !h-1.5 !w-1.5" /> armed</span></div>
          <p className="mt-3 display-font text-[13px] font-semibold text-[#dbe6df]">Redis defense mesh</p>
          <div className="mt-3 h-1 overflow-hidden rounded-full bg-white/[.08]"><div className="h-full w-[92%] rounded-full bg-gradient-to-r from-lime-300 to-cyan-300" /></div>
          <p className="mt-2 text-[10px] text-[#78908a]">92% of risk paths currently sealed</p>
        </div>
        <div className="flex items-center gap-2 px-2 text-[10px] text-[#61706d]"><div className="h-1.5 w-1.5 rounded-full bg-[#19d9dc]" /> <span className="sidebar-copy">v2.4.0 · local edge</span></div>
      </div>
    </aside>
  );
}

function Topbar({ onCommand, onRefresh, refreshing, connected, lastSynced }: { onCommand: () => void; onRefresh: () => void; refreshing: boolean; connected: boolean; lastSynced: string }) {
  return (
    <header className="flex h-[72px] items-center justify-between border-b border-white/[.07] px-5 sm:px-8 lg:px-10">
      <div className="mobile-topbar hidden items-center gap-3"><LogoMark /><span className="display-font text-sm font-bold">CacheGuard</span></div>
      <div className="hidden items-center gap-2 text-[11px] text-[#70817d] md:flex"><span className="text-[#a5b3ae]">Operations</span><span>/</span><span>Command center</span><span>/</span><span className="text-[#e7f2e7]">Live view</span></div>
      <div className="ml-auto flex items-center gap-2 sm:gap-3">
        <button onClick={onCommand} className="glass-soft hidden h-9 items-center gap-3 rounded-xl px-3 text-[11px] font-semibold text-[#81908d] transition hover:border-lime-300/30 hover:text-[#d9ffa4] sm:flex"><Search size={14} /><span>Search commands</span><kbd className="rounded-md border border-white/10 px-1.5 py-0.5 font-mono text-[9px] text-[#5f706b]">⌘ K</kbd></button>
        <button aria-label="Open command palette" onClick={onCommand} className="icon-button h-9 w-9 rounded-xl sm:hidden"><Search size={15} /></button>
        <button aria-label="Notifications" onClick={() => toast.info("No new critical alerts", { description: "Your current risk queue is within the expected operating band." })} className="icon-button relative h-9 w-9 rounded-xl"><Bell size={15} /><span className="absolute right-2 top-2 h-1.5 w-1.5 rounded-full bg-[#ff7e62]" /></button>
        <div className="hidden h-5 w-px bg-white/10 sm:block" />
        <div className="hidden items-center gap-2.5 sm:flex"><div className="grid h-8 w-8 place-items-center rounded-full border border-cyan-200/20 bg-cyan-300/10 text-cyan-100"><UserRound size={14} /></div><div className="leading-none"><p className="text-[11px] font-bold text-[#dae5dd]">Nilesh R.</p><p className="mt-1 text-[9px] font-medium text-[#72827e]">Platform admin</p></div><ChevronDown size={13} className="text-[#687a76]" /></div>
        <button onClick={onRefresh} className="ml-1 flex h-9 items-center gap-2 rounded-xl border border-lime-300/25 bg-lime-300/10 px-3 text-[10px] font-bold uppercase tracking-[.13em] text-[#b9ecff] transition hover:bg-lime-300/15 active:scale-[.97]" aria-label="Sync metrics"><RefreshCw size={13} className={refreshing ? "animate-spin" : ""} /><span className="hidden sm:inline">Sync</span></button>
      </div>
    </header>
  );
}

function MetricCard({ label, value, change, icon: IconComponent, tone, footnote }: { label: string; value: string; change: string; icon: Icon; tone: "acid" | "cyan" | "coral" | "lilac"; footnote: string }) {
  return (
    <div className={`metric-card glass noise ${tone}`}>
      <div className="relative flex items-start justify-between"><span className="metric-label">{label}</span><div className="grid h-7 w-7 place-items-center rounded-lg border border-white/10 bg-white/[.04] text-[#a1b0ac]"><IconComponent size={14} /></div></div>
      <div className="relative mt-4 flex items-end justify-between gap-2"><span className="metric-value">{value}</span><span className={`mb-1 flex items-center gap-0.5 text-[10px] font-bold ${change.startsWith("-") ? "text-[#ff9c87]" : "text-[#61d7ff]"}`}>{change.startsWith("-") ? <ArrowDownRight size={12} /> : <ArrowUpRight size={12} />}{change}</span></div>
      <p className="relative mt-2 text-[10px] text-[#6f817c]">{footnote}</p>
    </div>
  );
}

function MiniBars() {
  const bars = [34, 52, 42, 65, 56, 72, 66, 88, 75, 92, 82, 96];
  return <div className="flex h-8 items-end gap-1">{bars.map((height, index) => <span key={index} className="w-1.5 rounded-t-sm bg-cyan-300/60" style={{ height: `${height}%`, opacity: .35 + index / 20 }} />)}</div>;
}

function SignalOrb() {
  return (
    <div className="hero-orb-wrap relative flex h-full min-h-[230px] items-center justify-center overflow-hidden rounded-xl border border-cyan-200/10 bg-[#0c1519]/70">
      <div className="absolute inset-0 tiny-grid opacity-35" />
      <div className="absolute left-[19%] top-[24%] h-1 w-1 rounded-full bg-[#61d7ff] shadow-[0_0_12px_#61d7ff]" /><div className="absolute right-[20%] top-[32%] h-1.5 w-1.5 rounded-full bg-[#19d9dc] shadow-[0_0_14px_#19d9dc]" /><div className="absolute bottom-[23%] left-[28%] h-1 w-1 rounded-full bg-[#ff7e62] shadow-[0_0_12px_#ff7e62]" />
      <div className="signal-orb"><div className="scanline" /><div className="signal-orb-core" /></div>
      <div className="absolute bottom-4 left-4 right-4 flex items-center justify-between text-[9px] font-bold uppercase tracking-[.14em] text-[#73857f]"><span>signal integrity</span><span className="text-[#61d7ff]">99.98%</span></div>
    </div>
  );
}

function TrafficChart() {
  return (
    <div className="h-[255px] w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={trafficData} margin={{ top: 10, right: 8, bottom: 0, left: -18 }}>
          <defs>
            <linearGradient id="allowedFill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor="#61d7ff" stopOpacity={.24} /><stop offset="100%" stopColor="#61d7ff" stopOpacity={0} /></linearGradient>
            <linearGradient id="blockedFill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor="#ff7e62" stopOpacity={.17} /><stop offset="100%" stopColor="#ff7e62" stopOpacity={0} /></linearGradient>
          </defs>
          <CartesianGrid stroke="rgba(218,239,234,.065)" vertical={false} />
          <XAxis dataKey="time" axisLine={false} tickLine={false} tick={{ fill: "#64746f", fontSize: 10 }} interval={2} dy={10} />
          <YAxis axisLine={false} tickLine={false} tick={{ fill: "#64746f", fontSize: 10 }} width={28} />
          <Tooltip contentStyle={{ background: "#11191b", border: "1px solid rgba(200,255,82,.25)", borderRadius: 12, boxShadow: "0 16px 40px rgba(0,0,0,.35)", fontSize: 11 }} labelStyle={{ color: "#61d7ff", marginBottom: 4 }} itemStyle={{ color: "#e9efe9" }} cursor={{ stroke: "rgba(200,255,82,.3)" }} />
          <ReferenceLine x="09:50" stroke="#ff7e62" strokeDasharray="4 4" strokeOpacity={.55} label={{ value: "burst", fill: "#ff9c87", fontSize: 9, position: "insideTopRight" }} />
          <Area type="monotone" dataKey="allowed" name="Allowed" stroke="#61d7ff" strokeWidth={2.2} fill="url(#allowedFill)" activeDot={{ r: 4, fill: "#61d7ff", stroke: "#0b1112", strokeWidth: 2 }} />
          <Area type="monotone" dataKey="blocked" name="Blocked" stroke="#ff7e62" strokeWidth={1.8} fill="url(#blockedFill)" activeDot={{ r: 4, fill: "#ff7e62", stroke: "#0b1112", strokeWidth: 2 }} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

function RiskTable({ entries }: { entries: HotlistEntry[] }) {
  const [selected, setSelected] = useState<string | null>(null);
  return (
    <div id="risk-hotlist" className="glass overflow-hidden rounded-xl">
      <div className="flex items-center justify-between border-b border-white/[.07] px-5 py-4"><div><div className="flex items-center gap-2"><span className="eyebrow">Risk hotlist</span><span className="status-pill coral !px-2 !py-1">live queue</span></div><h2 className="mt-1 display-font text-[17px] font-bold tracking-[-.04em] text-[#eef8ee]">Actors to watch</h2></div><button onClick={() => toast.success("Hotlist export prepared", { description: "CSV export is ready for your incident workflow." })} className="icon-button h-8 w-8 rounded-lg" aria-label="Export risk hotlist"><Download size={14} /></button></div>
      <div className="overflow-x-auto"><table className="w-full min-w-[620px] text-left"><thead><tr className="text-[9px] font-bold uppercase tracking-[.14em] text-[#63736f]"><th className="px-5 py-3 font-bold">Identity</th><th className="px-3 py-3 font-bold">Signal</th><th className="px-3 py-3 font-bold">Risk score</th><th className="px-3 py-3 font-bold">Gateway action</th><th className="px-4 py-3" /></tr></thead><tbody>{entries.map((entry) => <tr key={entry.username} className="table-row text-[11px] text-[#b9c6c0]"><td className="px-5 py-3.5"><div className="flex items-center gap-3"><div className="grid h-7 w-7 place-items-center rounded-lg bg-white/[.05] text-[#8eaaa2]"><UserRound size={13} /></div><div><p className="font-semibold text-[#e5eee6]">{entry.username}</p><p className="mt-0.5 font-mono text-[9px] text-[#64746f]">active · stream indexed</p></div></div></td><td className="px-3 py-3.5"><span className={`tag ${entry.label.includes("Credential") ? "coral" : entry.label.includes("Device") ? "cyan" : "lime"}`}>{entry.label}</span></td><td className="px-3 py-3.5"><div className="flex items-center gap-2"><div className="score-bar"><span style={{ width: `${entry.score}%` }} /></div><span className="font-mono font-bold text-[#dce8dd]">{entry.score}</span></div></td><td className="px-3 py-3.5"><span className={`status-pill ${entry.status === "blocked" ? "coral" : entry.status === "captcha" ? "cyan" : "lime"} !px-2 !py-1`}>{entry.status}</span></td><td className="px-4 py-3.5 text-right"><button className="icon-button h-7 w-7 rounded-lg" aria-label={`Inspect ${entry.username}`} onClick={() => { setSelected(entry.username); toast.info(`Inspecting ${entry.username}`, { description: `${entry.label} · risk score ${entry.score}` }); }}><MoreHorizontal size={14} /></button></td></tr>)}</tbody></table></div>
      {selected && <div className="border-t border-lime-300/10 bg-lime-300/[.035] px-5 py-2.5 text-[10px] text-[#9aaa97]">Focused actor: <span className="font-semibold text-[#b9ecff]">{selected}</span> · next login is evaluated against the global risk hotlist.</div>}
    </div>
  );
}

function DetectionList({ showAll, onToggle }: { showAll: boolean; onToggle: () => void }) {
  const detections = showAll ? [...seedDetections, { time: "10:29:43", actor: "ravi.singh", signal: "Device drift", source: "84.17.34.210", score: 28, severity: "monitored" as const }, { time: "10:24:11", actor: "maria.lopez", signal: "IP velocity", source: "91.205.72.44", score: 24, severity: "monitored" as const }] : seedDetections;
  return (
    <div id="login-streams" className="glass rounded-xl p-5"><div className="flex items-center justify-between"><div><p className="eyebrow">Login streams</p><h2 className="mt-1 display-font text-[17px] font-bold tracking-[-.04em] text-[#eef8ee]">Recent detections</h2></div><button onClick={onToggle} className="text-[10px] font-bold uppercase tracking-[.12em] text-[#9cb09f] transition hover:text-[#61d7ff]">{showAll ? "Collapse" : "View all events"}</button></div><div className="mt-4 space-y-1">{detections.map((item) => <div key={`${item.time}-${item.actor}`} className="flex items-center gap-3 border-t border-white/[.06] py-3"><div className={`grid h-8 w-8 shrink-0 place-items-center rounded-lg ${item.severity === "critical" ? "bg-[#ff7e62]/10 text-[#ff9c87]" : item.severity === "elevated" ? "bg-[#19d9dc]/10 text-[#9df8f4]" : "bg-white/[.05] text-[#94a49f]"}`}><AlertTriangle size={14} /></div><div className="min-w-0 flex-1"><div className="flex min-w-0 items-center gap-2"><span className="truncate text-[11px] font-bold text-[#dae5dd]">{item.actor}</span><span className={`tag hidden sm:inline ${item.severity === "critical" ? "coral" : item.severity === "elevated" ? "cyan" : "lime"}`}>{item.signal}</span></div><p className="mt-1 truncate font-mono text-[9px] text-[#657671]">{item.source} <span className="px-1 text-[#43514e]">·</span> {item.time} UTC</p></div><div className="text-right"><p className="font-mono text-[12px] font-bold text-[#dce8dd]">{item.score}</p><p className="mt-0.5 text-[9px] text-[#64746f]">risk</p></div></div>)}</div></div>
  );
}

function RedisTopology() {
  return (
    <div id="redis-topology" className="glass rounded-xl p-5"><div className="flex items-start justify-between"><div><p className="eyebrow">Redis topology</p><h2 className="mt-1 display-font text-[17px] font-bold tracking-[-.04em] text-[#eef8ee]">Defense mesh</h2></div><span className="status-pill lime !px-2 !py-1"><span className="lime-dot !h-1.5 !w-1.5" /> healthy</span></div><div className="isometric-scene relative mt-4 h-[150px] overflow-hidden rounded-2xl border border-white/[.06] bg-[#0b1215] tiny-grid"><div className="node-line left-[33%] top-[48%] w-[31%] rotate-[-25deg]" /><div className="node-line left-[33%] top-[49%] w-[31%] rotate-[23deg]" /><div className="node-line left-[55%] top-[48%] w-[28%] rotate-[-4deg]" /><div className="node-line left-[55%] top-[51%] w-[27%] rotate-[25deg]" /><div className="redis-node left-[13%] top-[38%]"><Shield size={18} /></div><div className="redis-node left-[42%] top-[35%] !border-cyan-200/30 !bg-cyan-300/10 !text-cyan-200"><Database size={18} /></div><div className="redis-node left-[79%] top-[12%] !h-9 !w-9 !rounded-xl !border-[#bf8cff]/35 !bg-[#bf8cff]/10 !text-[#d1b6ff]"><Layers3 size={16} /></div><div className="redis-node left-[79%] top-[62%] !h-9 !w-9 !rounded-xl !border-[#ff7e62]/35 !bg-[#ff7e62]/10 !text-[#ffb1a0]"><LockKeyhole size={16} /></div><div className="absolute bottom-3 left-3 rounded-md bg-black/30 px-2 py-1 font-mono text-[8px] text-[#60726c]">redis-stack://local:6379</div></div><div className="mt-4 grid grid-cols-3 gap-2 border-t border-white/[.06] pt-3"><div><p className="text-[9px] uppercase tracking-[.12em] text-[#64746f]">Streams</p><p className="mt-1 font-mono text-[13px] font-bold text-[#dce8dd]">24</p></div><div><p className="text-[9px] uppercase tracking-[.12em] text-[#64746f]">HLL sets</p><p className="mt-1 font-mono text-[13px] font-bold text-[#dce8dd]">37</p></div><div><p className="text-[9px] uppercase tracking-[.12em] text-[#64746f]">Latency</p><p className="mt-1 font-mono text-[13px] font-bold text-[#61d7ff]">1.2ms</p></div></div></div>
  );
}

function CommandPalette({ onClose }: { onClose: () => void }) {
  const [query, setQuery] = useState("");
  const commands = ["Open traffic shield", "Inspect risk hotlist", "Export incident report", "Run sample attack", "View Redis topology"];
  const filtered = commands.filter((command) => command.toLowerCase().includes(query.toLowerCase()));
  return <div className="command-overlay" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><div className="command-panel"><div className="flex items-center gap-3 border-b border-white/[.08] px-4 py-4"><Search size={18} className="text-[#61d7ff]" /><input autoFocus value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search CacheGuard commands..." className="command-input" /><button onClick={onClose} className="icon-button h-7 w-7 rounded-lg" aria-label="Close command palette"><X size={14} /></button></div><div className="p-2">{filtered.map((command, index) => <button key={command} onClick={() => { toast.success(`${command} queued`); onClose(); }} className="flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left text-[12px] text-[#becbc3] transition hover:bg-white/[.06] hover:text-[#b9ecff]"><span className="grid h-7 w-7 place-items-center rounded-lg bg-white/[.05] text-[#82938e]"><Command size={14} /></span>{command}<span className="ml-auto font-mono text-[9px] text-[#53635f]">{index < 2 ? "↵" : "⌘ ↵"}</span></button>)}{filtered.length === 0 && <div className="px-3 py-8 text-center text-[12px] text-[#73857f]">No commands match “{query}”.</div>}</div><div className="flex items-center gap-4 border-t border-white/[.08] px-4 py-3 text-[9px] uppercase tracking-[.12em] text-[#5f706b]"><span><kbd className="mr-1 rounded border border-white/10 px-1">↑↓</kbd> navigate</span><span><kbd className="mr-1 rounded border border-white/10 px-1">↵</kbd> run</span><span><kbd className="mr-1 rounded border border-white/10 px-1">esc</kbd> close</span></div></div></div>;
}

function AnimatedCursor() {
  const dotRef = useRef<HTMLDivElement>(null);
  const ringRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (window.matchMedia("(pointer: coarse)").matches) return;
    let targetX = window.innerWidth / 2;
    let targetY = window.innerHeight / 2;
    let ringX = targetX;
    let ringY = targetY;
    let frame = 0;

    const move = (event: PointerEvent) => {
      targetX = event.clientX;
      targetY = event.clientY;
      if (dotRef.current) dotRef.current.style.transform = `translate3d(${targetX}px, ${targetY}px, 0)`;
    };
    const tick = () => {
      ringX += (targetX - ringX) * 0.16;
      ringY += (targetY - ringY) * 0.16;
      if (ringRef.current) ringRef.current.style.transform = `translate3d(${ringX}px, ${ringY}px, 0)`;
      frame = window.requestAnimationFrame(tick);
    };

    window.addEventListener("pointermove", move, { passive: true });
    frame = window.requestAnimationFrame(tick);
    return () => {
      window.removeEventListener("pointermove", move);
      window.cancelAnimationFrame(frame);
    };
  }, []);

  return <><div ref={ringRef} className="cursor-ring" aria-hidden="true" /><div ref={dotRef} className="cursor-dot" aria-hidden="true" /></>;
}

export default function Home() {
  const { stats, hotlist, lastSynced, connected, refreshing, refresh } = useCacheGuardData();
  const [activeNav, setActiveNav] = useState<NavId>("command");
  const [showCommand, setShowCommand] = useState(false);
  const [showAllEvents, setShowAllEvents] = useState(false);
  const [range, setRange] = useState("1h");
  const [paused, setPaused] = useState(false);
  const blockRate = stats.totalRequests ? ((stats.blockedRequests / stats.totalRequests) * 100).toFixed(2) : "0.65";

  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") { event.preventDefault(); setShowCommand(true); }
      if (event.key === "Escape") setShowCommand(false);
    };
    window.addEventListener("keydown", listener);
    return () => window.removeEventListener("keydown", listener);
  }, []);

  const handleNavigate = (id: NavId, target: string) => {
    setActiveNav(id);
    document.getElementById(target)?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  const syncLabel = connected ? `live · ${lastSynced}` : lastSynced;
  const rangeCaption = useMemo(() => range === "1h" ? "last 60 minutes" : range === "6h" ? "last 6 hours" : "last 24 hours", [range]);

  return (
    <div className="app-shell">
      <AnimatedCursor />
      <div className="mesh-field" />
      <Sidebar active={activeNav} onNavigate={handleNavigate} />
      <div className="main-shell relative ml-[246px] min-h-screen">
        <Topbar onCommand={() => setShowCommand(true)} onRefresh={() => void refresh(true)} refreshing={refreshing} connected={connected} lastSynced={lastSynced} />
        <main id="command-center" className="mx-auto max-w-[1500px] px-5 py-7 sm:px-8 sm:py-9 lg:px-10">
          <section className="fade-up hero-grid grid grid-cols-[1.5fr_.75fr] gap-5">
            <div className="glass noise relative overflow-hidden rounded-xl px-6 py-7 sm:px-8 sm:py-8"><div className="relative"><div className="flex flex-wrap items-center gap-2"><span className="status-pill lime"><span className="lime-dot !h-1.5 !w-1.5" /> perimeter armed</span><span className="text-[10px] font-bold uppercase tracking-[.14em] text-[#62736e]">{syncLabel}</span></div><h1 className="mt-5 max-w-[650px] display-font text-[clamp(32px,4.2vw,60px)] font-semibold leading-[.98] tracking-[-.075em] text-[#eef8ee]">Edge posture: <span className="text-[#61d7ff]">nominal.</span></h1><p className="mt-5 max-w-[580px] text-[13px] leading-6 text-[#9aa9a5]">Request policy, login scoring, and Redis signals are operating inside the configured perimeter.</p><div className="mt-7 flex flex-wrap items-center gap-3"><button onClick={() => handleNavigate("traffic", "traffic-shield")} className="flex h-10 items-center gap-2 rounded-xl bg-[#61d7ff] px-4 text-[11px] font-extrabold uppercase tracking-[.12em] text-[#101609] transition hover:bg-[#d8ff84] active:scale-[.97]"><Activity size={14} /> Inspect traffic</button><button onClick={() => setShowCommand(true)} className="glass-soft flex h-10 items-center gap-2 rounded-xl px-4 text-[11px] font-bold uppercase tracking-[.12em] text-[#b9c7c0] transition hover:border-cyan-200/30 hover:text-[#a0ffff]"><TerminalSquare size={14} /> Open command palette</button></div></div><div className="mt-7 grid max-w-[570px] grid-cols-3 gap-2 border-t border-white/[.07] pt-4"><div><p className="text-[9px] uppercase tracking-[.13em] text-[#667771]">Current window</p><p className="mt-1 font-mono text-[13px] font-bold text-[#dce8dd]">60 sec</p></div><div><p className="text-[9px] uppercase tracking-[.13em] text-[#667771]">Rate limit</p><p className="mt-1 font-mono text-[13px] font-bold text-[#dce8dd]">200 req</p></div><div><p className="text-[9px] uppercase tracking-[.13em] text-[#667771]">Worker cadence</p><p className="mt-1 font-mono text-[13px] font-bold text-[#61d7ff]">5 sec</p></div></div></div>
            <SignalOrb />
          </section>

          <section className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <div className="fade-up delay-1"><MetricCard label="Requests shielded" value={formatCompact(stats.totalRequests)} change="+12.8%" icon={Network} tone="acid" footnote="all API traffic · rolling total" /></div>
            <div className="fade-up delay-2"><MetricCard label="Rate-limit blocks" value={formatCompact(stats.blockedRequests)} change="+4.2%" icon={ShieldAlert} tone="coral" footnote={`${blockRate}% of requests · 429 responses`} /></div>
            <div className="fade-up delay-3"><MetricCard label="Flagged logins" value={formatCompact(stats.flaggedLogins)} change="+8.6%" icon={Users} tone="cyan" footnote="stream events · scored by worker" /></div>
            <div className="fade-up delay-4"><MetricCard label="Hotlist identities" value={String(stats.hotlistSize || 37)} change="-3.1%" icon={LockKeyhole} tone="lilac" footnote="risk:hotlist · sorted by score" /></div>
          </section>

          <section id="traffic-shield" className="mt-5 grid gap-5 xl:grid-cols-[1.45fr_.72fr]">
            <div className="glass rounded-xl p-5 sm:p-6"><div className="flex flex-wrap items-start justify-between gap-4"><div><div className="flex items-center gap-2"><p className="eyebrow">Traffic shield</p><span className="status-pill cyan !px-2 !py-1"><Wifi size={10} /> stream</span></div><h2 className="mt-1 display-font text-[19px] font-bold tracking-[-.045em] text-[#eef8ee]">Request pressure</h2><p className="mt-1 text-[11px] text-[#748580]">Allowed vs. rejected traffic · {rangeCaption}</p></div><div className="flex items-center gap-1 rounded-xl border border-white/[.07] bg-white/[.025] p-1">{["1h", "6h", "24h"].map((item) => <button key={item} onClick={() => setRange(item)} className={`rounded-lg px-2.5 py-1.5 text-[10px] font-bold ${range === item ? "bg-white/[.1] text-[#b9ecff]" : "text-[#677773] hover:text-[#b5c2bc]"}`}>{item}</button>)}</div></div><div className="mt-5 flex items-center gap-4 text-[10px] text-[#82928d]"><span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-[#61d7ff]" /> allowed</span><span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-[#ff7e62]" /> blocked</span><span className="ml-auto hidden items-center gap-1.5 text-[#667773] sm:flex"><TrendingUp size={12} className="text-[#61d7ff]" /> baseline stable</span></div><div className="mt-1"><TrafficChart /></div></div>
            <div className="glass rounded-xl p-5 sm:p-6"><div className="flex items-start justify-between"><div><p className="eyebrow">Anomaly index</p><h2 className="mt-1 display-font text-[19px] font-bold tracking-[-.045em] text-[#eef8ee]">Threat pressure</h2></div><button className="icon-button h-8 w-8 rounded-lg" aria-label="More anomaly options" onClick={() => toast.info("Anomaly index is calculated from request pressure and login signals.")}><MoreHorizontal size={14} /></button></div><div className="mt-7 flex items-end justify-between"><div><span className="display-font text-[52px] font-semibold leading-none tracking-[-.08em] text-[#61d7ff]">24</span><span className="ml-2 text-[11px] font-semibold text-[#879690]">/ 100</span><p className="mt-3 flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-[.12em] text-[#61d7ff]"><Check size={12} /> low risk band</p></div><MiniBars /></div><div className="mt-7 space-y-3"><div className="flex items-center justify-between text-[10px]"><span className="text-[#71817c]">Device flood</span><span className="font-mono text-[#d7e3da]">12%</span></div><div className="h-1.5 overflow-hidden rounded-full bg-white/[.07]"><div className="h-full w-[12%] rounded-full bg-[#19d9dc]" /></div><div className="flex items-center justify-between text-[10px]"><span className="text-[#71817c]">Credential stuffing</span><span className="font-mono text-[#d7e3da]">7%</span></div><div className="h-1.5 overflow-hidden rounded-full bg-white/[.07]"><div className="h-full w-[7%] rounded-full bg-[#ff7e62]" /></div><div className="flex items-center justify-between text-[10px]"><span className="text-[#71817c]">IP velocity</span><span className="font-mono text-[#d7e3da]">5%</span></div><div className="h-1.5 overflow-hidden rounded-full bg-white/[.07]"><div className="h-full w-[5%] rounded-full bg-[#bf8cff]" /></div></div></div>
          </section>

          <section className="mt-5"><RiskTable entries={hotlist} /></section>
          <section className="mt-5 grid gap-5 xl:grid-cols-[1.1fr_.9fr]"><DetectionList showAll={showAllEvents} onToggle={() => setShowAllEvents((value) => !value)} /><RedisTopology /></section>

          <section className="mt-5 flex flex-wrap items-center justify-between gap-4 rounded-lg border border-white/[.07] bg-white/[.02] px-5 py-4"><div className="flex items-center gap-3"><div className="grid h-8 w-8 place-items-center rounded-lg bg-[#61d7ff]/10 text-[#61d7ff]"><Sparkles size={15} /></div><div><p className="text-[11px] font-bold text-[#dce8dd]">Demo harness ready</p><p className="mt-0.5 text-[10px] text-[#6f817c]">Run the included load tests to make the charts move.</p></div></div><div className="flex flex-wrap items-center gap-2"><button onClick={() => { setPaused((value) => !value); toast.success(paused ? "Telemetry stream resumed" : "Telemetry stream paused"); }} className="glass-soft flex h-9 items-center gap-2 rounded-xl px-3 text-[10px] font-bold uppercase tracking-[.12em] text-[#aebdb5] hover:text-[#b9ecff]">{paused ? <Play size={13} /> : <Pause size={13} />}{paused ? "Resume" : "Pause"}</button><button onClick={() => toast.success("Sample attack queued", { description: "The load-test harness can now be run locally against /api/login." })} className="flex h-9 items-center gap-2 rounded-xl bg-[#61d7ff] px-3 text-[10px] font-extrabold uppercase tracking-[.12em] text-[#101609] hover:bg-[#d8ff84]"><Zap size={13} /> Run sample</button><button onClick={() => window.open("https://github.com/nilesh24rit/CacheGuard", "_blank", "noopener,noreferrer")} className="icon-button h-9 w-9 rounded-xl" aria-label="Open CacheGuard repository"><ExternalLink size={14} /></button></div></section>
          <footer className="flex flex-wrap items-center justify-between gap-3 px-1 py-6 text-[9px] uppercase tracking-[.13em] text-[#52625d]"><span>CacheGuard console · built for the edge</span><span className="flex items-center gap-2"><Clock3 size={11} /> {connected ? "live API connected" : "demo data · connect localhost:8080"}</span></footer>
        </main>
      </div>
      {showCommand && <CommandPalette onClose={() => setShowCommand(false)} />}
    </div>
  );
}
