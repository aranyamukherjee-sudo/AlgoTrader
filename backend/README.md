# AlgoTrader Market Data Backend

FastAPI service that receives live market data from FYERS and exposes it
to the AlgoTrader Android application.

## Endpoints

GET /health
GET /quotes

## Environment variables

FYERS_APP_ID
FYERS_ACCESS_TOKEN

## Local Linux/Docker run

docker build -t algotrader-backend .

docker run --rm \
  -p 8765:8765 \
  -e FYERS_APP_ID="$FYERS_APP_ID" \
  -e FYERS_ACCESS_TOKEN="$FYERS_ACCESS_TOKEN" \
  algotrader-backend
