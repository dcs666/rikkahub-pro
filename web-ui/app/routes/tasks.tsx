import * as React from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { ChevronLeft, ListRestart, Loader2, Plus, Trash2, XCircle } from "lucide-react";
import { Button } from "~/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "~/components/ui/card";
import { Input } from "~/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog";
import { Badge } from "~/components/ui/badge";
import { Textarea } from "~/components/ui/textarea";
import { Switch } from "~/components/ui/switch";
import api from "~/services/api";

interface TaskDto {
  id: string;
  type: string;
  status: string;
  description: string;
  createdAt: number;
  updatedAt: number;
  completedAt: number;
  errorMessage: string;
  pollCount: number;
  result: string;
}

const STATUS_STYLES: Record<string, string> = {
  PENDING: "bg-yellow-500/15 text-yellow-700",
  RUNNING: "bg-blue-500/15 text-blue-700",
  COMPLETED: "bg-green-500/15 text-green-700",
  FAILED: "bg-red-500/15 text-red-700",
  CANCELLED: "bg-gray-500/15 text-gray-600",
};

function formatTime(ms: number): string {
  if (!ms) return "-";
  return new Date(ms).toLocaleString();
}

export default function TasksPage() {
  const { t } = useTranslation();
  const [tasks, setTasks] = React.useState<TaskDto[]>([]);
  const [loading, setLoading] = React.useState(true);

  // 创建定时器表单
  const [delayMinutes, setDelayMinutes] = React.useState("10");
  const [message, setMessage] = React.useState("");
  const [autoAi, setAutoAi] = React.useState(false);
  const [stepsText, setStepsText] = React.useState("");
  const [creating, setCreating] = React.useState(false);

  const loadTasks = React.useCallback(async () => {
    try {
      const data = await api.get<{ tasks: TaskDto[] }>("/api/tasks?limit=50");
      setTasks(data.tasks ?? []);
    } catch (e) {
      toast.error(`Failed to load tasks: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    loadTasks();
    const timer = setInterval(loadTasks, 10_000);
    return () => clearInterval(timer);
  }, [loadTasks]);

  const createTimer = async () => {
    const minutes = Number(delayMinutes);
    if (!Number.isFinite(minutes) || minutes <= 0) {
      toast.error("Delay must be a positive number of minutes");
      return;
    }
    setCreating(true);
    try {
      const steps = stepsText
        .split("\n")
        .map((s) => s.trim())
        .filter(Boolean);
      await api.post("/api/tasks/timer", {
        delayMs: minutes * 60_000,
        message: message.trim() || "Timer",
        autoAi,
        steps: steps.length > 0 ? steps : undefined,
      });
      toast.success(`Timer set for ${minutes}m${steps.length > 1 ? ` (workflow: ${steps.length} steps)` : ""}`);
      setMessage("");
      setStepsText("");
      loadTasks();
    } catch (e) {
      toast.error(`Failed to create timer: ${(e as Error).message}`);
    } finally {
      setCreating(false);
    }
  };

  const cancelTask = async (id: string) => {
    try {
      await api.post(`/api/tasks/${id}/cancel`);
      loadTasks();
    } catch (e) {
      toast.error(`Failed to cancel: ${(e as Error).message}`);
    }
  };

  const rerunTask = async (id: string) => {
    try {
      await api.post(`/api/tasks/${id}/rerun`);
      loadTasks();
    } catch (e) {
      toast.error(`Failed to rerun: ${(e as Error).message}`);
    }
  };

  const deleteTask = async (id: string) => {
    try {
      await api.delete(`/api/tasks/${id}`);
      loadTasks();
    } catch (e) {
      toast.error(`Failed to delete: ${(e as Error).message}`);
    }
  };

  const activeCount = tasks.filter((t) => t.status === "PENDING" || t.status === "RUNNING").length;

  return (
    <div className="mx-auto max-w-3xl p-4 sm:p-6">
      <div className="mb-4 flex items-center gap-2">
        <Link to="/">
          <Button variant="ghost" size="icon">
            <ChevronLeft className="size-4" />
          </Button>
        </Link>
        <h1 className="flex-1 text-lg font-semibold">Background Tasks</h1>
        {activeCount > 0 && <Badge className="bg-blue-500/15 text-blue-700">{activeCount} active</Badge>}
        <Dialog>
          <DialogTrigger asChild>
            <Button size="sm">
              <Plus className="mr-1 size-4" /> New timer
            </Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Create timer</DialogTitle>
              <DialogDescription>
                Fires once after the delay; with AI execution it can run a multi-step workflow.
              </DialogDescription>
            </DialogHeader>
            <div className="grid gap-3 py-2">
              <div className="grid gap-1.5">
                <label className="text-sm font-medium" htmlFor="delay">Delay (minutes)</label>
                <Input
                  id="delay"
                  type="number"
                  min={1}
                  value={delayMinutes}
                  onChange={(e) => setDelayMinutes(e.target.value)}
                />
              </div>
              <div className="grid gap-1.5">
                <label className="text-sm font-medium" htmlFor="msg">Message</label>
                <Input
                  id="msg"
                  value={message}
                  onChange={(e) => setMessage(e.target.value)}
                  placeholder="What should I remind you about?"
                />
              </div>
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium" htmlFor="autoai">AI executes on fire</label>
                <Switch id="autoai" checked={autoAi} onCheckedChange={setAutoAi} />
              </div>
              {autoAi && (
                <div className="grid gap-1.5">
                  <label className="text-sm font-medium" htmlFor="steps">Workflow steps (one per line)</label>
                  <Textarea
                    id="steps"
                    rows={4}
                    value={stepsText}
                    onChange={(e) => setStepsText(e.target.value)}
                    placeholder={"Research the latest progress\nSummarize into 5 key points\nSave to my memory"}
                  />
                </div>
              )}
            </div>
            <DialogFooter>
              <Button onClick={createTimer} disabled={creating}>
                {creating && <Loader2 className="mr-1 size-4 animate-spin" />}
                Create
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>

      {loading ? (
        <div className="flex justify-center py-12">
          <Loader2 className="size-6 animate-spin text-muted-foreground" />
        </div>
      ) : tasks.length === 0 ? (
        <Card>
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            No tasks yet. Create a timer or CI monitor to get started.
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-2">
          {tasks.map((task) => (
            <Card key={task.id}>
              <CardContent className="p-3">
                <div className="flex items-center gap-2">
                  <Badge className={STATUS_STYLES[task.status] ?? ""}>{task.status}</Badge>
                  <Badge variant="outline">{task.type}</Badge>
                  <span className="min-w-0 flex-1 truncate text-sm font-medium">{task.description}</span>
                  <div className="flex shrink-0 items-center gap-1">
                    {(task.status === "PENDING" || task.status === "RUNNING") && (
                      <Button variant="ghost" size="sm" onClick={() => cancelTask(task.id)} title="Cancel">
                        <XCircle className="size-4 text-muted-foreground" />
                      </Button>
                    )}
                    {task.status === "FAILED" && (
                      <Button variant="ghost" size="sm" onClick={() => rerunTask(task.id)} title="Rerun">
                        <ListRestart className="size-4 text-muted-foreground" />
                      </Button>
                    )}
                    {task.status !== "PENDING" && task.status !== "RUNNING" && (
                      <Button variant="ghost" size="sm" onClick={() => deleteTask(task.id)} title="Delete">
                        <Trash2 className="size-4 text-muted-foreground" />
                      </Button>
                    )}
                  </div>
                </div>
                <div className="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 pl-1 text-xs text-muted-foreground">
                  <span>Created: {formatTime(task.createdAt)}</span>
                  {task.completedAt > 0 && <span>Completed: {formatTime(task.completedAt)}</span>}
                  {task.errorMessage && <span className="text-red-600">{task.errorMessage}</span>}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
