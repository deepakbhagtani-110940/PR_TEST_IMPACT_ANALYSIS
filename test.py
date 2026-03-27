import uuid
from typing import List, Optional
from datetime import datetime
from pydantic import BaseModel, EmailStr, Field
from passlib.context import CryptContext

# --- Security Configuration ---
# CryptContext handles secure password hashing (bcrypt)
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

# --- Mock Database ---
# In a real app, these would be SQL or NoSQL tables
users_db = {
    "user@example.com": {
        "hashed_password": pwd_context.hash("secure_password123"),
        "id": "user_01"
    }
}

# --- Data Models (Validation) ---
class LoginRequest(BaseModel):
    email: EmailStr
    password: str

class OrderItem(BaseModel):
    product_id: str
    quantity: int = Field(gt=0)
    price: float

class CheckoutRequest(BaseModel):
    user_id: str
    items: List[OrderItem]
    shipping_address: str

# --- Service Logic ---
class OrderService:
    @staticmethod
    def authenticate_user(login_data: LoginRequest) -> Optional[str]:
        """Validates credentials and returns user_id."""
        user = users_db.get(login_data.email)
        if user and pwd_context.verify(login_data.password, user["hashed_password"]):
            return user["id"]
        return None

    @staticmethod
    def process_checkout(checkout_data: CheckoutRequest):
        """Simulates payment processing and order persistence."""
        total_amount = sum(item.price * item.quantity for item in checkout_data.items)
        
        # In production, you'd call a Payment Gateway (Stripe/PayPal) here
        order_id = str(uuid.uuid4())
        
        return {
            "order_id": order_id,
            "status": "success",
            "total": round(total_amount, 2),
            "timestamp": datetime.now().isoformat()
        }

# --- Execution Simulation ---
if __name__ == "__main__":
    print("--- 1. Login Phase ---")
    login_attempt = LoginRequest(email="user@example.com", password="secure_password123")
    user_id = OrderService.authenticate_user(login_attempt)

    if user_id:
        print(f"Login Successful! User ID: {user_id}")
        
        print("\n--- 2. Checkout Phase ---")
        cart = CheckoutRequest(
            user_id=user_id,
            items=[
                OrderItem(product_id="sku_abc", quantity=2, price=19.99),
                OrderItem(product_id="sku_xyz", quantity=1, price=50.00)
            ],
            shipping_address="123 Tech Lane, Silicon Valley, CA"
        )
        
        order_receipt = OrderService.process_checkout(cart)
        print("Order Confirmed:")
        print(order_receipt)
    else:
        print("Login Failed: Invalid Credentials")
