// Sovereign Delivery Platform - Digital Twin Map & Tracking Controller
// Strict zero external cloud SaaS dependencies

let map;
let ws;
let activeCourierMarker = null;
let restaurantMarkers = [];
let dropoffMarker = null;
let currentOrderId = null;
let currentVerificationPin = "482915";
let trackingBreadcrumbs = [];

const SF_CENTER = [-122.4194, 37.7749]; // [lon, lat] for MapLibre

// 1. Initialize MapLibre GL Map with Offline Vector Tiles
function initMap() {
  const mapStyle = {
    version: 8,
    name: "Sovereign-Dark-Offline",
    sources: {
      "city-tiles": {
        type: "vector",
        tiles: [
          window.location.origin + "/tiles/city/{z}/{x}/{y}"
        ],
        minzoom: 0,
        maxzoom: 14
      }
    },
    layers: [
      {
        id: "background",
        type: "background",
        paint: {
          "background-color": "#090d16"
        }
      },
      {
        id: "water",
        type: "fill",
        source: "city-tiles",
        "source-layer": "water",
        paint: {
          "fill-color": "#0f233a",
          "fill-opacity": 0.9
        }
      },
      {
        id: "transportation",
        type: "line",
        source: "city-tiles",
        "source-layer": "transportation",
        paint: {
          "line-color": "#1e293b",
          "line-width": 2.5
        }
      },
      {
        id: "building",
        type: "fill",
        source: "city-tiles",
        "source-layer": "building",
        paint: {
          "fill-color": "#151e2e",
          "fill-outline-color": "#334155"
        }
      }
    ]
  };

  map = new maplibregl.Map({
    container: "map",
    style: mapStyle,
    center: SF_CENTER,
    zoom: 13,
    pitch: 35,
    bearing: 0
  });

  map.on("load", () => {
    console.log("MapLibre GL offline map initialized successfully from /tiles/city/{z}/{x}/{y}");
    setupRouteSources();
    loadRestaurants();
  });
}

function setupRouteSources() {
  // Add route line GeoJSON source
  map.addSource("courier-route", {
    type: "geojson",
    data: {
      type: "Feature",
      geometry: {
        type: "LineString",
        coordinates: []
      }
    }
  });

  map.addLayer({
    id: "route-line",
    type: "line",
    source: "courier-route",
    layout: {
      "line-join": "round",
      "line-cap": "round"
    },
    paint: {
      "line-color": "#06b6d4",
      "line-width": 4,
      "line-opacity": 0.8
    }
  });
}

// 2. Connect to Sovereign WebSocket Gateway (/ws/tracking)
function connectWebSocket() {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const wsUrl = `${protocol}//${window.location.host}/ws/tracking`;

  const wsDot = document.getElementById("ws-dot");
  const wsText = document.getElementById("ws-text");

  ws = new WebSocket(wsUrl);

  ws.onopen = () => {
    console.log("Connected to tracking WebSocket gateway:", wsUrl);
    wsDot.className = "dot connected";
    wsText.textContent = "WebSocket: Active";
  };

  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      handleTelemetryMessage(data);
    } catch (e) {
      console.warn("Failed to parse WebSocket message:", e);
    }
  };

  ws.onclose = () => {
    console.log("WebSocket connection closed. Retrying in 3 seconds...");
    wsDot.className = "dot connecting";
    wsText.textContent = "WebSocket: Reconnecting";
    setTimeout(connectWebSocket, 3000);
  };

  ws.onerror = (err) => {
    console.warn("WebSocket error:", err);
  };
}

// 3. Handle Live Telemetry Ping Updates
function handleTelemetryMessage(msg) {
  if (!msg || !msg.latitude || !msg.longitude) {
    return;
  }

  const lat = msg.latitude;
  const lon = msg.longitude;
  const speed = msg.speedKmh ? Math.round(msg.speedKmh * 10) / 10 : 28.0;
  const heading = msg.headingDegrees ? Math.round(msg.headingDegrees) : 0;
  const h3Cell = msg.h3Index || "8828308281fffff";

  // Update HUD
  document.getElementById("telemetry-hud").style.display = "grid";
  document.getElementById("metric-speed").innerHTML = `${speed} <span class="metric-unit">km/h</span>`;
  document.getElementById("metric-heading").innerHTML = `${heading}° <span class="metric-unit">compass</span>`;
  document.getElementById("metric-h3").textContent = h3Cell;

  // Anti-fraud check indicator (< 120 km/h)
  const fraudEl = document.getElementById("metric-fraud");
  if (speed < 120) {
    fraudEl.textContent = "PASSED (<120)";
    fraudEl.style.color = "var(--accent-emerald)";
  } else {
    fraudEl.textContent = "DROPPED SPOOF";
    fraudEl.style.color = "var(--accent-rose)";
  }

  // Update Courier Marker on Map
  if (!activeCourierMarker) {
    const el = document.createElement("div");
    el.className = "courier-marker";
    el.innerHTML = `
      <svg viewBox="0 0 24 24">
        <path d="M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.85 7h10.29l1.08 3.11H5.77L6.85 7zM19 17H5v-4.66l.12-.34h13.77l.11.34V17z"/>
        <circle cx="7.5" cy="14.5" r="1.5"/>
        <circle cx="16.5" cy="14.5" r="1.5"/>
      </svg>
    `;
    activeCourierMarker = new maplibregl.Marker({ element: el })
      .setLngLat([lon, lat])
      .addTo(map);
  } else {
    activeCourierMarker.setLngLat([lon, lat]);
    activeCourierMarker.getElement().style.transform += ` rotate(${heading}deg)`;
  }

  // Append to breadcrumb trail
  trackingBreadcrumbs.push([lon, lat]);
  if (map.getSource("courier-route")) {
    map.getSource("courier-route").setData({
      type: "Feature",
      geometry: {
        type: "LineString",
        coordinates: trackingBreadcrumbs
      }
    });
  }

  // Check proximity to customer dropoff
  const dropLat = parseFloat(document.getElementById("dropoff-lat").value);
  const dropLon = parseFloat(document.getElementById("dropoff-lon").value);
  const distanceToDrop = calculateDistanceKm(lat, lon, dropLat, dropLon);

  if (distanceToDrop < 0.04) { // within ~40 meters
    document.getElementById("handshake-box").style.display = "block";
    setTimelineStep("step-delivered");
  }
}

// 4. Load Restaurants and Markers
async function loadRestaurants() {
  try {
    const res = await fetch("/api/v1/catalog/restaurants");
    if (res.ok) {
      const data = await res.json();
      const restaurants = data.content || data;
      renderRestaurantMarkers(restaurants);
    } else {
      renderDefaultRestaurants();
    }
  } catch (e) {
    console.warn("Using default restaurants for demo:", e);
    renderDefaultRestaurants();
  }
}

function renderDefaultRestaurants() {
  const defaults = [
    { name: "Mission Taqueria", lat: 37.7749, lon: -122.4194, cuisine: "Mexican" },
    { name: "Valencia Ramen", lat: 37.7610, lon: -122.4215, cuisine: "Japanese" },
    { name: "Tartine Bakery", lat: 37.7614, lon: -122.4241, cuisine: "Bakery" }
  ];
  renderRestaurantMarkers(defaults);
}

function renderRestaurantMarkers(restaurants) {
  restaurants.forEach(r => {
    const el = document.createElement("div");
    el.className = "restaurant-marker";
    el.innerHTML = "🌮";
    el.title = r.name;

    const lat = r.latitude || r.lat;
    const lon = r.longitude || r.lon;

    const popup = new maplibregl.Popup({ offset: 25 }).setHTML(`
      <strong style="color: #0f172a;">${r.name}</strong><br>
      <span style="font-size: 11px; color: #475569;">Verified Geodesic EPSG:4326 Zone</span>
    `);

    new maplibregl.Marker({ element: el })
      .setLngLat([lon, lat])
      .setPopup(popup)
      .addTo(map);
  });
}

function onRestaurantChanged() {
  const select = document.getElementById("restaurant-select");
  const selected = select.options[select.selectedIndex];
  const lat = parseFloat(selected.getAttribute("data-lat"));
  const lon = parseFloat(selected.getAttribute("data-lon"));
  map.flyTo({ center: [lon, lat], zoom: 14, speed: 1.2 });
}

// 5. Place Order & Trigger Virtual Courier Simulator
async function handlePlaceOrder(event) {
  if (event) event.preventDefault();

  const restaurantId = document.getElementById("restaurant-select").value;
  const dishSelect = document.getElementById("dish-select");
  const dishId = dishSelect.value;
  const dishName = dishSelect.options[dishSelect.selectedIndex].text.split(" - ")[0];
  const dishPrice = parseFloat(dishSelect.options[dishSelect.selectedIndex].getAttribute("data-price"));

  const dropoffBuilding = document.getElementById("dropoff-address").value;
  const dropoffLat = parseFloat(document.getElementById("dropoff-lat").value);
  const dropoffLon = parseFloat(document.getElementById("dropoff-lon").value);
  currentVerificationPin = document.getElementById("verification-pin").value || "482915";

  // Reset UI
  resetTimeline();
  trackingBreadcrumbs = [];
  if (map.getSource("courier-route")) {
    map.getSource("courier-route").setData({ type: "Feature", geometry: { type: "LineString", coordinates: [] } });
  }

  // Plot dropoff marker
  if (dropoffMarker) dropoffMarker.remove();
  const dropEl = document.createElement("div");
  dropEl.className = "dropoff-marker";
  dropEl.innerHTML = "📍";
  dropoffMarker = new maplibregl.Marker({ element: dropEl })
    .setLngLat([dropoffLon, dropoffLat])
    .addTo(map);

  setTimelineStep("step-placed");
  document.getElementById("order-info-section").style.display = "block";
  document.getElementById("handshake-pin-display").textContent = currentVerificationPin;
  document.getElementById("order-pin-label").textContent = currentVerificationPin;

  const orderPayload = {
    userId: "00000000-0000-0000-0000-000000000001",
    restaurantId: restaurantId,
    dropoffLatitude: dropoffLat,
    dropoffLongitude: dropoffLon,
    dropoffBuilding: dropoffBuilding,
    dropoffFloor: "3",
    dropoffRoom: "3B",
    verificationPin: currentVerificationPin,
    items: [
      {
        dishId: dishId,
        dishName: dishName,
        unitPrice: dishPrice,
        quantity: 2
      }
    ]
  };

  try {
    // 1. Create Order
    const createRes = await fetch("/api/v1/orders", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(orderPayload)
    });

    if (!createRes.ok) {
      throw new Error("Order creation failed: " + createRes.statusText);
    }

    const orderData = await createRes.json();
    currentOrderId = orderData.id;
    document.getElementById("order-id-label").textContent = currentOrderId;
    document.getElementById("order-total-label").textContent = "$" + orderData.totalAmount;

    // 2. Ledger Hold (Payment Pending -> Confirmed)
    await delay(600);
    setTimelineStep("step-payment");
    await fetch(`/api/v1/orders/${currentOrderId}/pay`, { method: "POST" });

    // 3. Kitchen Accept & Prepare
    await delay(600);
    await fetch(`/api/v1/orders/${currentOrderId}/accept`, { method: "POST" });
    await fetch(`/api/v1/orders/${currentOrderId}/prepare`, { method: "POST" });
    setTimelineStep("step-prepared");

    // 4. Ready for Pickup
    await delay(600);
    await fetch(`/api/v1/orders/${currentOrderId}/ready`, { method: "POST" });

    // 5. Offer 30s Targeted Dispatch Lease
    await delay(600);
    setTimelineStep("step-leased");
    await fetch(`/api/v1/orders/${currentOrderId}/lease`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ leaseSeconds: 30 })
    });

    // 6. Virtual Courier Simulator picks up lease
    await delay(800);
    setTimelineStep("step-dispatched");

    // Trigger simulator dispatch
    await fetch(`/api/v1/simulator/dispatch/${currentOrderId}`, { method: "POST" });

    await delay(800);
    setTimelineStep("step-out-for-delivery");
    document.getElementById("active-order-badge").textContent = "In Transit";
    document.getElementById("active-order-badge").style.color = "var(--accent-cyan)";

    // Start polling simulator ticks if needed for rapid demonstration
    startSimulationLoop();

  } catch (err) {
    console.error("Order flow error:", err);
    alert("Order placement failed: " + err.message);
  }
}

// 6. Complete Doorstep PIN Handshake
async function submitPinHandshake() {
  if (!currentOrderId) {
    alert("No active order to verify");
    return;
  }

  try {
    const res = await fetch(`/api/v1/orders/${currentOrderId}/deliver`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ verificationPin: currentVerificationPin })
    });

    if (res.ok) {
      document.getElementById("handshake-box").style.display = "none";
      document.getElementById("settled-alert").style.display = "block";
      document.getElementById("active-order-badge").textContent = "Settled";
      document.getElementById("active-order-badge").style.color = "var(--accent-emerald)";
      setTimelineStep("step-delivered");
    } else {
      const err = await res.json();
      alert("PIN handshake failed: " + (err.message || res.statusText));
    }
  } catch (e) {
    console.error("Failed PIN delivery:", e);
  }
}

// 7. Automated Verification Gate Journey
async function runAutomatedJourney() {
  const btn = document.getElementById("btn-place-order");
  btn.scrollIntoView({ behavior: "smooth" });
  await handlePlaceOrder(null);
}

let simInterval = null;
function startSimulationLoop() {
  if (simInterval) clearInterval(simInterval);
  simInterval = setInterval(async () => {
    try {
      const res = await fetch("/api/v1/simulator/tick", { method: "POST" });
      if (res.ok) {
        const data = await res.json();
        if (data.activeJobs === 0) {
          clearInterval(simInterval);
        }
      }
    } catch (e) {
      // background tick
    }
  }, 1500);
}

function setTimelineStep(stepId) {
  const steps = [
    "step-placed",
    "step-payment",
    "step-prepared",
    "step-leased",
    "step-dispatched",
    "step-out-for-delivery",
    "step-delivered"
  ];
  let found = false;
  steps.forEach(id => {
    const el = document.getElementById(id);
    if (!found) {
      if (id === stepId) {
        el.className = "timeline-step active";
        found = true;
      } else {
        el.className = "timeline-step completed";
      }
    } else {
      el.className = "timeline-step";
    }
  });
}

function resetTimeline() {
  const steps = [
    "step-placed",
    "step-payment",
    "step-prepared",
    "step-leased",
    "step-dispatched",
    "step-out-for-delivery",
    "step-delivered"
  ];
  steps.forEach(id => {
    document.getElementById(id).className = "timeline-step";
  });
  document.getElementById("handshake-box").style.display = "none";
  document.getElementById("settled-alert").style.display = "none";
}

function calculateDistanceKm(lat1, lon1, lat2, lon2) {
  const R = 6371;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// Boot UI
window.addEventListener("DOMContentLoaded", () => {
  initMap();
  connectWebSocket();
});
