import { useEffect, useState } from "react";
import { Sidebar, type Route } from "./components/Sidebar";
import { PlanPage } from "./pages/PlanPage";
import { ImportPage } from "./pages/ImportPage";

const fromHash = (): Route => (window.location.hash === "#/import" ? "import" : "plan");

export default function App() {
  const [route, setRoute] = useState<Route>(fromHash);

  useEffect(() => {
    const onHash = () => setRoute(fromHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  const go = (r: Route) => {
    window.location.hash = r === "import" ? "#/import" : "#/";
  };

  return (
    <div className="shell">
      <Sidebar route={route} />
      <main className="main">
        {route === "import" ? <ImportPage onGoPlan={() => go("plan")} /> : <PlanPage onGoImport={() => go("import")} />}
      </main>
    </div>
  );
}
