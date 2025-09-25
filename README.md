# HarborWatch Lite

A minimal container-monitoring demo built with **Java/Spring Boot** and **PostgreSQL**, designed for easy deployment on an AWS EC2 instance using an IAM role for SES and Docker log rotation. This "lite" branch keeps the stack small (app + db) to fit within AWS free-tier limits and remain easy to understand.

## Features

- **Spring Boot (Java 17)**:
  - Health endpoint: `/actuator/health`
  - Load endpoints to generate CPU, memory, and database pressure
  - Background scheduler writing sample metrics to PostgreSQL every 5 seconds
  - Debug endpoints to inspect database state
- **PostgreSQL 15** managed by Flyway (schema & types, IST timestamps)
- **AWS SES** integration using EC2 instance IAM role (no AWS keys required)
- **Docker log rotation** (`json-file`) to cap disk usage

## Repository Layout

```
harborwatch/
├── Dockerfile                        # Java 17, non-root runtime
├── docker-compose.yml                # Minimal stack (app + db)
├── .env.example                      # Environment variable template
├── README.md                         # This file
├── pom.xml                           # Spring Boot 3.5, Flyway, JPA, Web, Actuator
└── src/
    └── main/
        ├── java/dev/harborwatch/...
        │   ├── HarborWatchApplication.java   # Main app with @EnableScheduling
        │   ├── load/LoadController.java      # /api/* endpoints
        │   ├── load/LoadService.java         # CPU/Mem/DB logic
        │   ├── load/MetricsScheduler.java    # Writes metrics every 5s
        │   └── debug/*.java                  # Read-only debug endpoints
        └── resources/
            ├── application.yml               # Wiring + logging levels
            ├── logback-spring.xml            # Human-friendly stdout logs
            └── db/migration/                 # V1/V2 Flyway migrations
```

## Quick Start (Local)

1. **Create `.env` file**  
   Copy the example or create manually:
   ```bash
   cp .env.example .env
   ```
   Or:
   ```bash
   cat > .env <<'ENV'
   SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/appdb
   SPRING_DATASOURCE_USERNAME=postgres
   SPRING_DATASOURCE_PASSWORD=postgres123
   TZ=Asia/Kolkata
   JAVA_OPTS=-Duser.timezone=Asia/Kolkata
   # AWS_REGION=ap-south-1 (optional for local SES testing with creds; IF NOT PROVIDED IT WILL CONSIDE us-east-1)
   ENV
   ```

2. **Build and run**  
   ```bash
   docker compose -f docker-compose.yml up -d --build
   docker logs -f hw-app
   ```

3. **Sanity checks**  
   Verify the app is running:
   ```bash
   curl -s http://localhost:8080/actuator/health
   curl -s http://localhost:8080/api/debug/summary
   ```
   Test load endpoints:
   ```bash
   curl -s "http://localhost:8080/api/cpu-intensive?iterations=500000"
   curl -s "http://localhost:8080/api/database-intensive?ops=200"
   ```

## Endpoints

### Health
- `GET /actuator/health` → `{"status":"UP"}`

### Load/Stress
- `GET /api/cpu-intensive?iterations=1000000` → Generates CPU load
- `GET /api/memory-intensive?sizeMb=50` → Allocates memory
- `GET /api/database-intensive?ops=1000` → Stresses database
- `GET /api/combined-stress?durationSec=20` → Combined CPU/memory/DB load

### Debug (Read-Only)
- `GET /api/debug/summary` → Row counts
- `GET /api/debug/performance/recent` → Last 10 `performance_data` rows
- `GET /api/debug/computations/recent` → Last 10 `computation_results` rows
- `GET /api/debug/now` → App clock vs. DB clock and last rows

*Note*: Logs are not stored in the database (12-factor app principle). Tables store metrics and summaries only.

## EC2 Deployment (SES via IAM Role)

### A. Create IAM Role
1. Go to **IAM → Roles → Create role → EC2**.
2. Attach a minimal policy:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": ["ses:SendEmail", "ses:SendRawEmail"],
       "Resource": "*"
     }]
   }
   ```
3. Name the role (e.g., `EC2_HarborWatch_SES_Role`).
4. If SES is in sandbox mode, verify sender/recipient emails in SES or request production access.

### B. Launch EC2 (Free-Tier Friendly)
- **AMI**: Amazon Linux 2023 or Ubuntu 22.04
- **Instance Type**: `t2.micro` or `t3.micro`
- **IAM Role**: Attach `EC2_HarborWatch_SES_Role`
- **Security Group**:
  - Inbound: `22` (SSH, your IP only), `8080` (app)
  - Outbound: Allow HTTPS (for SES)

### C. Install Docker & Clone Code
```bash
# SSH into EC2
ssh -i your-key.pem ec2-user@EC2_PUBLIC_IP

# Install Docker & tools (Amazon Linux 2023)
sudo dnf -y update
sudo dnf -y install docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user  # Re-login or use sudo

# (Optional) AWS CLI
curl -sSLo awscliv2.zip https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip
unzip -q awscliv2.zip && sudo ./aws/install

# Clone repo
git clone https://github.com/<your-username>/harborwatch.git
cd harborwatch
```

### D. Configure `.env` (No AWS Keys)
```bash
cat > .env <<'ENV'
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/appdb
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres123
TZ=Asia/Kolkata
JAVA_OPTS=-Duser.timezone=Asia/Kolkata
AWS_REGION=us-east-1
ENV
```

### E. Run the Stack
```bash
docker compose -f docker-compose.yml up -d --build
docker logs -f hw-app
```

### F. Verify IAM Role
```bash
aws sts get-caller-identity
```
This should show the assumed-role ARN for your EC2 role.

### G. Access the App
From your laptop:
```
http://EC2_PUBLIC_IP:8080/actuator/health
```

## AWS Authentication
- No AWS keys are stored in `.env`.
- The AWS SDK v2 in `EmailService` uses `DefaultCredentialsProvider`, which fetches temporary credentials from the EC2 Instance Metadata Service via the attached IAM role.
- Only the `AWS_REGION` is required (set in `.env` or client configuration).

## Docker Log Rotation
To prevent disk usage issues, logs are rotated using Docker's `json-file` driver.

### Option A: Per-Service (Recommended)
In `docker-compose.yml`:
```yaml
services:
  db:
    logging:
      driver: "json-file"
      options: { max-size: "10m", max-file: "5" }
  app:
    logging:
      driver: "json-file"
      options: { max-size: "10m", max-file: "5" }
```
Apply:
```bash
docker compose -f docker-compose.yml up -d --build
docker inspect hw-app --format '{{json .HostConfig.LogConfig}}'
```

### Option B: Daemon-Wide
```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json >/dev/null <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "5" }
}
JSON
sudo systemctl restart docker
```

### List Rotated Logs
```bash
sudo -i <<'EOF'
for c in hw-app hw-db; do
  echo "== $c =="; L=$(docker inspect "$c" --format '{{.LogPath}}' 2>/dev/null) || true
  [ -n "$L" ] && [ -e "$L" ] && ls -lh "$L"* || echo "(no log file found)"
  echo
done
EOF
```
