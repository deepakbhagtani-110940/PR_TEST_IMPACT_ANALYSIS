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
