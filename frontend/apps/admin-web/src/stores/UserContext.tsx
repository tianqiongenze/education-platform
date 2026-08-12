import { createContext, useContext, useState, type ReactNode } from 'react';
import type { User } from '@ep/shared';

interface UserContextValue {
  user: User | null;
  setUser: (user: User | null) => void;
  logout: () => void;
}

const UserContext = createContext<UserContextValue>({
  user: null,
  setUser: () => {},
  logout: () => {},
});

interface UserProviderProps {
  children: ReactNode;
  initialUser?: User | null;
}

export function UserProvider({ children, initialUser = null }: UserProviderProps) {
  const [user, setUser] = useState<User | null>(initialUser);
  const logout = () => setUser(null);
  return (
    <UserContext.Provider value={{ user, setUser, logout }}>
      {children}
    </UserContext.Provider>
  );
}

export function useUserStore() {
  return useContext(UserContext);
}
