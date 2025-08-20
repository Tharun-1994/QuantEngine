QuantEngine
  QuantEngine is a modular, rule‑driven backtesting engine focused on clean, configurable strategy definitions and reproducible results. It centralizes backtest parameters (universe, allocations, entry/exit rules, risk, and timing) and produces portfolios, orders, equity curves, and performance metrics suitable for research or live-simulation workflows.

Features

Declarative strategy rules
Define entry/exit conditions like
(rsi_14 < 10) AND (hv_100 > 100) AND (atr_10 < 13) with AND/OR connectors.

Pluggable indicators
Compute indicators from daily closes (e.g., RSI, ATR, historical volatility) and expose them to the rule engine.

Portfolio simulation
Slot-based allocation, position sizing, rebalance policies, basic risk management (stop loss / take profit with timing).

Data adapters
CSV-first ingestion with a clean interface to swap in other data providers (API/DB).

Outputs for dashboards
Tradelist, equity curve, and summary stats exported to JSON/CSV—ready for Plotly or your FastAPI/Tailwind dashboard.

Extensible & testable
Clear boundaries for Strategy → Signals → Portfolio → Orders → Reports, with unit tests.
