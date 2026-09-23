/** Контракт бэкенда (docs/front data.txt). Эти поля приходят всегда. */
export interface OrderItemBase {
  erp_code: string;
  supplier_id: string;
  supplier_name: string;
  supplier_article: string;
  name: string;
  unit: string;
  order_qty: number;
  explanation: string;
}

/**
 * Предлагаемые бэкенду поля (docs/design.md, раздел «Фронтенд: контракт API»).
 * Пока бэкенд их не отдаёт — UI прячет соответствующие колонки и блоки.
 */
export interface OrderItemExtra {
  urgency?: "high" | "medium" | "low";
  abc_xyz?: string;
  forecast_qty?: number;
  safety_stock?: number;
  free_stock?: number;
  in_transit?: number;
  moq?: number;
  demand_per_month?: number;
  cover_months?: number | null;
  unit_cost?: number;
}

export type OrderItem = OrderItemBase & OrderItemExtra;

export interface OrdersResponse {
  items: OrderItem[];
}

export interface ApproveRequest {
  supplier_id: string;
  lines: { erp_code: string; order_qty: number; reason?: string }[];
}

export interface ApproveResponse {
  order_id: string;
  approved_at: string;
}

export interface ApiError {
  error: { code: string; message: string; request_id?: string };
}
